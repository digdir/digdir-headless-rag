(ns digdir.auth.core
  (:require [buddy.sign.jwt :as jwt]
            [tick.core :as t]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-http.client :as http2]
            [clojure.data.json :as json]
            [nano-id.core :refer [nano-id]]
            [datahike.api :as d]
            [hiccup2.core :as h]
            [digdir.data.db :as db]
            [digdir.config.accessor :as cfg]
            [digdir.config.core :as config-core]
            [digdir.i18n :as i18n])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

;; ### Key Metrics to Track for Security
;; 1. **Failed login attempts**: Monitor for brute force attempts.
;; 2. **IP addresses**: Track unusual IPs or geolocation changes.
;; 3. **Session duration**: Detect abnormally long sessions.
;; 4. **Successful logins**: Correlate with user behavior or unusual patterns.
;; 5. **Password reset requests**: Frequent requests can indicate compromise.
;; 6. **Account creation activity**: Watch for bots or bulk account creation.
;; 7. **Access to admin areas**: Monitor elevated permissions usage.

;; ### Common Administration Functions
;; - [ ] **Create new account**: Adding users with roles.
;; - [ ] **Reset password**: Manual or user-initiated.
;; - [ ] **Disable account**: Temporary or permanent deactivation.
;; - [ ] **Update roles/permissions**: Adjust user access.

;; DB transactions
(defn create-new-user [{:keys [email creator-id] :as data}]
  (let [conn (db/get-conn)]
    (println "called create new user with: " email " and data " data)
    (if-not (d/entity @conn [:user/email email])
      (let [user-id (nano-id)]
        (println "creating new user with: " email " and data " data)
        (d/transact conn [{:user/id user-id
                           :user/email email
                           :user/preferred-language "en"
                           :user/created (str (t/now))
                           :user/created-by (or creator-id user-id)}]))
      {:error "User already exists"})))

;; DB queries
(defn accounts-created-by [db email]
  (d/q '[:find (pull ?created-account [*])
         :in $ ?creator-email
         :where
         [?creator :user/email ?creator-email]
         [?creator :user/id ?creator-id]
         [?created-account :user/created-by ?creator-id]
         [(not= ?created-account ?creator)]]
       db email))

(defn all-accounts [db]
  (d/q '[:find (pull ?e [*])
         :where
         [?e :user/id]
         [?e :user/email]
         [?e :user/created-by]]
       db))

(defn user-by-email [email]
  (let [conn (db/get-conn)]
    (d/q '[:find (pull ?e [*]) .
           :in $ ?email
           :where
           [?e :user/email ?email]]
         @conn email)))

(defn user-by-id [user-id]
  (let [conn (db/get-conn)]
    (d/q '[:find (pull ?e [*]) .
           :in $ ?user-id
           :where
           [?e :user/id ?user-id]]
         @conn user-id)))

(defn user-preferred-language
  "Return a supported language code string for a user map."
  [user]
  (name (i18n/normalize-language (:user/preferred-language user))))

(defn current-user-preferred-language
  "Get the current user preferred language from an Electric http-request."
  [http-request]
  (user-preferred-language http-request))

(defn set-user-preferred-language!
  "Persist a user's preferred language and return the normalized language code."
  [user-id language]
  (let [conn (db/get-conn)
        normalized-language (name (i18n/normalize-language language))
        user-eid (d/q '[:find ?e .
                        :in $ ?user-id
                        :where
                        [?e :user/id ?user-id]]
                     @conn user-id)]
    (when-not user-eid
      (throw (ex-info "User not found" {:user-id user-id})))
    (d/transact conn [{:db/id user-eid
                       :user/preferred-language normalized-language}])
    normalized-language))

(defn current-user-id
  "Get the current user ID from an Electric http-request.
   Use this in e/server blocks: (e/server (auth/current-user-id e/http-request))

   Args:
     http-request - The Electric http-request binding (e/http-request)

   Returns: User ID string or nil if not authenticated"
  [http-request]
  (:user/id http-request))

(defn current-user-email
  "Get the current user email from an Electric http-request.
   Use this in e/server blocks: (e/server (auth/current-user-email e/http-request))

   Args:
     http-request - The Electric http-request binding (e/http-request)

   Returns: User email string or nil if not authenticated"
  [http-request]
  (:user/email http-request))

(def secret
  (delay
    (or (config-core/get-jwt-secret)
        (throw (ex-info "JWT_SECRET must be configured" {})))))

(def ^:private code-ttl-minutes
  "How long a freshly minted code validates for. Unchanged from the in-memory
   implementation."
  10)

(def ^:private expired-code-grace-minutes
  "How long an EXPIRED code's record is kept after it stops validating.

   Nothing validates during this window — it exists purely so the server can
   still answer \"why did this fail\". Delete the row at expiry and `expired`
   collapses into `never asked for one`, which is the diagnosis gap #63 is
   about. See `code-status`."
  60)

(defn- now-ms [] (System/currentTimeMillis))

(defonce ^:private confirmation-code-rng (SecureRandom.))

(defn- hmac-sha256-bytes
  [key message]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes ^String key StandardCharsets/UTF_8)
                               "HmacSHA256"))
    (.doFinal mac (.getBytes ^String message StandardCharsets/UTF_8))))

(defn- confirmation-code-digest
  "Return an email-bound, domain-separated digest. A database reader cannot
   enumerate the six-digit code without also possessing JWT_SECRET."
  [email confirmation-code]
  (let [digest (hmac-sha256-bytes @secret
                                  (str "digdir-confirmation-code-v1\n"
                                       email "\n" confirmation-code))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- secure-digest=
  [left right]
  (and (string? left)
       (string? right)
       (MessageDigest/isEqual (.getBytes ^String left StandardCharsets/UTF_8)
                              (.getBytes ^String right StandardCharsets/UTF_8))))

(defn- cas-failure?
  [e]
  (str/includes? (or (.getMessage ^Throwable e) "") ":db.fn/cas failed"))

(defn purge-stale-confirmation-codes!
  "Retract confirmation codes that expired more than the grace window ago.

   Called on every mint, which is the only moment new rows appear, so the
   table stays bounded without a scheduler. Returns the number retracted."
  [conn]
  (let [cutoff (- (now-ms) (* expired-code-grace-minutes 60 1000))
        stale (d/q '[:find [?e ...]
                     :in $ ?cutoff
                     :where
                     [?e :confirmation-code/expires-at ?expires-at]
                     [(< ?expires-at ?cutoff)]]
                   @conn cutoff)]
    (when (seq stale)
      (d/transact conn {:tx-data (mapv (fn [e] [:db/retractEntity e]) stale)}))
    (count stale)))

(defn generate-confirmation-code
  "Mint a confirmation code for `email` and store it.

   Stored in the database rather than process memory (#63): a code minted
   before a restart still validates afterwards, within its expiry. The email
   is a :db.unique/identity, so requesting a second code replaces the first —
   one code in flight per address, as before."
  [email]
  (let [conn (db/get-conn)
        confirmation-code (format "%06d" (.nextInt confirmation-code-rng 1000000))
        now (now-ms)]
    (purge-stale-confirmation-codes! conn)
    (d/transact conn {:tx-data [{:confirmation-code/email email
                                 :confirmation-code/code-digest
                                 (confirmation-code-digest email confirmation-code)
                                 :confirmation-code/created-at now
                                 ;; Reset explicitly: the email is a unique
                                 ;; identity, so this transaction UPSERTS over
                                 ;; any outstanding row. Without this the new
                                 ;; code inherits the old one's guess count,
                                 ;; and an attacker who burns four guesses
                                 ;; leaves the victim a replacement that dies
                                 ;; on its first typo (#211).
                                 :confirmation-code/failed-attempts 0
                                 :confirmation-code/expires-at (+ now (* code-ttl-minutes 60 1000))}]})
    confirmation-code))

(defn code-status
  "Why a confirmation code did or did not validate:

     :valid     matches an outstanding code that has not expired
     :mismatch  a code is outstanding for this address, and this is not it
     :expired   a code was outstanding for this address and its window passed
     :unknown   no record — never requested, already used, or swept

   THE POINT OF THE FOUR-WAY ANSWER, and the thing #63 is really about: before
   codes were persisted, a restart produced `:unknown` for a code the user had
   just been sent, which is the same answer a typo produces. The server could
   not tell them apart, so no error message could either.

   CALLERS MUST NOT LEAK THIS TO THE BROWSER. `digdir.api.http` renders one
   page for every non-:valid status and logs the distinction server-side.
   Telling an unauthenticated caller `expired` rather than `wrong` also tells
   someone guessing codes that the address has a login in flight."
  [email confirmation-code]
  (if-let [entity (d/entity @(db/get-conn) [:confirmation-code/email email])]
    (let [expires-at (:confirmation-code/expires-at entity)
          stored (:confirmation-code/code-digest entity)
          submitted (confirmation-code-digest email confirmation-code)]
      (cond
        (or (nil? expires-at) (<= expires-at (now-ms))) :expired
        (not (secure-digest= stored submitted)) :mismatch
        :else :valid))
    :unknown))

(defn valid-code?
  "True when `confirmation-code` matches an unexpired code outstanding for
   `email`. See `code-status` for why it failed."
  [email confirmation-code]
  (= :valid (code-status email confirmation-code)))

(def max-code-attempts
  "Wrong guesses a single code tolerates before it is destroyed (#211).

   WHY FIVE. The code is six digits, so one guess is 1-in-a-million and five
   are 1-in-200,000 — the guessing advantage this grants is negligible, while
   five is well past what a person mistyping a code they can see actually
   needs. The cost of being wrong in the strict direction is small and
   recoverable: the user requests another code.

   WHY A PER-CODE BUDGET IS THE REAL DEFENCE, and the per-IP one is not.
   This budget caps guesses against a given code at five NO MATTER HOW MANY
   SOURCE ADDRESSES TRY IT, so it is not evaded by rotating IPs the way a
   source-keyed limiter is. It lives in the database beside the code, so it
   also survives the restart that clears the in-memory limiter — which is the
   #63 defect over again, and the reason not to make this the atom's job.

   The two compose: five guesses per code, and the send budget in
   `digdir.api.rate-limit` bounds how fast new codes can be minted, so the
   sustained guess rate is bounded by the product rather than by either
   alone."
  5)

(defn register-code-failure!
  "Record a wrong guess against `email`'s outstanding code.

   Destroys the code once it has absorbed `max-code-attempts` wrong guesses:
   at that point the person holding the real code is better served by
   requesting another than by the code staying alive to be guessed at.

   Returns {:attempts n :destroyed? bool}, or nil when there was no code to
   fail against — a guess at an address with nothing outstanding tells us
   nothing about that address and must not create a row for it."
  ([email] (register-code-failure! email nil))
  ([email expected-digest]
   (let [conn (db/get-conn)]
     (loop []
       (when-let [entity (d/entity @conn [:confirmation-code/email email])]
         (let [eid (:db/id entity)
               stored-digest (:confirmation-code/code-digest entity)
               old-attempts (or (:confirmation-code/failed-attempts entity) 0)
               attempts (inc old-attempts)
               destroy? (>= attempts max-code-attempts)
               tx-data (cond-> [[:db/cas eid :confirmation-code/failed-attempts
                                  old-attempts attempts]]
                         expected-digest
                         (into [[:db/cas eid :confirmation-code/code-digest
                                 expected-digest expected-digest]])
                         destroy?
                         (conj [:db/retractEntity eid]))
               result (try
                        (d/transact conn {:tx-data tx-data})
                        :updated
                        (catch Throwable e
                          (if (cas-failure? e) :retry (throw e))))]
           (if (= :retry result)
             (recur)
             {:attempts attempts
              :destroyed? destroy?
              :code-digest stored-digest})))))))

(defn verify-and-consume-confirmation-code!
  "Atomically validate and consume a code, or atomically charge a mismatch to
   that exact code's failure budget. Returns the same status vocabulary as
   `code-status`, plus attempt metadata for a mismatch.

   The digest CAS is the replay boundary: only one concurrent request can
   consume a valid code, and a failure racing a replacement code retries
   against the replacement instead of charging its counter blindly."
  [email confirmation-code]
  (let [conn (db/get-conn)
        submitted (confirmation-code-digest email confirmation-code)]
    (loop []
      (if-let [entity (d/entity @conn [:confirmation-code/email email])]
        (let [eid (:db/id entity)
              expires-at (:confirmation-code/expires-at entity)
              stored (:confirmation-code/code-digest entity)]
          (cond
            (or (nil? expires-at) (<= expires-at (now-ms)))
            {:status :expired}

            (not (secure-digest= stored submitted))
            (let [failure (register-code-failure! email stored)]
              (if failure
                (assoc failure :status :mismatch)
                (recur)))

            :else
            (let [consumed-marker (str "consumed-" (java.util.UUID/randomUUID))
                  result (try
                           (d/transact conn
                                       {:tx-data [[:db/cas eid
                                                   :confirmation-code/code-digest
                                                   stored consumed-marker]
                                                  [:db/retractEntity eid]]})
                           :consumed
                           (catch Throwable e
                             (if (cas-failure? e) :retry (throw e))))]
              (if (= :retry result)
                (recur)
                {:status :valid}))))
        {:status :unknown}))))

(defn consume-confirmation-code!
  "Retract the outstanding code for `email` after a successful login.

   Single-use: a double-submit or a back-then-resubmit finds no record and
   gets the invalid-code page. `:keep-history? false` on both databases means
   the retraction actually removes the value rather than shelving it in
   history. Returns true when something was retracted."
  [email]
  (let [conn (db/get-conn)]
    (if-let [entity (d/entity @conn [:confirmation-code/email email])]
      (do (d/transact conn {:tx-data [[:db/retractEntity (:db/id entity)]]})
          true)
      false)))

(defn create-expiry [{:keys [multiplier timespan]}]
  (-> (t/now)
      (t/>> (t/new-duration multiplier timespan))
      (t/inst)
      (.getTime)))

(defn create-token [user-id expiry]
  (jwt/sign {:user-id user-id
             :expiry (/ expiry 1000)} @secret))

(defn verify-token [token]
  (try
    (let [{:keys [user-id expiry]} (jwt/unsign token @secret)
          current-time (/ (.getTime (t/inst (t/now))) 1000)]
      (if (< current-time expiry)
        {:valid true
         :user-id user-id
         :expiry (* expiry 1000)}
        {:valid false
         :reason "Token expired"}))
    (catch Exception e
      {:valid false :reason (str "Invalid token: " (.getMessage e))})))

;; Confirmation code

;; TODO: platform-scoped until admin auth is tenant-qualified.
(defn scaleway-region [tenant]
  (or (cfg/get {:tenant tenant} :services :scaleway-tem :region) "fr-par"))

(defn scaleway-project-id [tenant]
  (or (cfg/get {:tenant tenant} :services :scaleway-tem :project-id)
      (throw (ex-info "SCW_DEFAULT_PROJECT_ID must be configured" {}))))

(defn scaleway-api-key [tenant]
  (or (cfg/get {:tenant tenant} :services :scaleway-tem :api-key)
      (throw (ex-info "SCW_SECRET_KEY must be configured" {}))))

(defn scaleway-from-email [tenant]
  (or (cfg/get {:tenant tenant} :services :scaleway-tem :from-email) "no-reply@digdir.cloud"))

(defn scaleway-email-url [tenant]
  (str "https://api.scaleway.com/transactional-email/v1alpha1/regions/" (scaleway-region tenant) "/emails"))

(defn send-confirmation-code
  ([tenant to code] (send-confirmation-code tenant (scaleway-from-email tenant) to code))
  ([tenant from to code]
   (let [language (some-> (user-by-email to) user-preferred-language)]
     (http2/post (scaleway-email-url tenant)
                 {:headers {"Accept" "application/json"
                            "Content-Type" "application/json"
                            "X-Auth-Token" (scaleway-api-key tenant)}
                  :body (json/write-str
                         {:from {:email from}
                          :to [{:email to}]
                          :subject (i18n/translate language :auth/email-subject)
                          :project_id (scaleway-project-id tenant)
                          :text (str (i18n/translate language :auth/email-body-text code) "\n\n"
                                     (i18n/translate language :auth/email-support-text))
                          :html (str (h/html
                                      [:html
                                       [:body
                                        [:p (i18n/translate language :auth/email-body-html)]
                                        [:p {:style "font-size: 24px; font-weight: bold; color: #2D3748;"} code]
                                        [:p
                                         [:span (i18n/translate language :auth/email-support-html-text)]
                                         [:a {:href "mailto:hjelp@digdir.cloud"} "hjelp@digdir.cloud"]]]]))})}))))

;; Dev-only confirmation-code fallback
;;
;; First login needs a 6-digit code delivered by Scaleway TEM. With no email
;; service configured the send throws, which leaves a new contributor unable to
;; reach the admin UI at all. In dev we log the code instead.
;;
;; The switch is off by default and is only ever flipped by `src-dev/dev.cljc`.
;; `src-dev` is on the classpath of the :dev and :test aliases only — the :prod
;; alias carries `src-prod` instead (see server/deps.edn) — so a production
;; build contains no code path that can turn this on.

(defonce ^:private !dev-confirmation-code-logging (atom false))

(defn dev-confirmation-code-logging?
  "True when the dev confirmation-code fallback is armed."
  []
  @!dev-confirmation-code-logging)

(defn set-dev-confirmation-code-logging!
  "Arm/disarm the dev confirmation-code fallback. Called from the dev
   entrypoint; unreachable from a production build (see the comment above)."
  [enabled?]
  (reset! !dev-confirmation-code-logging (boolean enabled?)))

(defn email-configured?
  "True when the tenant has enough Scaleway TEM config for a send to be
   attempted. Mirrors the two values `send-confirmation-code` throws on.

   A config lookup that throws counts as unconfigured: on a freshly created DB
   `cfg/get` raises \"Canonical tenant root node not found\" for __global__
   rather than returning nil, which is precisely the first-run case this
   fallback exists for. Only ever reached in dev - `deliver-confirmation-code!`
   checks the dev switch first."
  [tenant]
  (try
    (boolean (and (cfg/get {:tenant tenant} :services :scaleway-tem :project-id)
                  (cfg/get {:tenant tenant} :services :scaleway-tem :api-key)))
    (catch Exception _ false)))

(defn dev-code-log-message
  "The line the dev fallback writes to the server log. Grep for `dev-login`."
  [to code]
  (str "[dev-login] No email service configured. Confirmation code for "
       to " is " code
       " — dev-only fallback; configure Scaleway TEM to send real mail."))

(defn deliver-confirmation-code!
  "Deliver `code` to `to` for `tenant`.

   Normally sends the code by email. When the dev fallback is armed and the
   email service is unconfigured — or the send fails — the code is written to
   the server log instead so login can complete without Scaleway credentials.

   Returns :sent when the code went to the email service, :logged when it went
   to the server log. Outside dev the send path is unchanged, exceptions
   included."
  [tenant to code]
  (if (and (dev-confirmation-code-logging?)
           (not (email-configured? tenant)))
    (do (log/warn (dev-code-log-message to code))
        :logged)
    (try
      (send-confirmation-code tenant to code)
      :sent
      (catch Exception e
        (if (dev-confirmation-code-logging?)
          (do (log/warn e "Sending the confirmation code failed; falling back to the dev log.")
              (log/warn (dev-code-log-message to code))
              :logged)
          (throw e))))))

;; Email Validation

(defn starts-or-ends-with? [s s-check]
  (or (str/starts-with? s s-check)
      (str/ends-with? s s-check)))

(defn consecutive-dots? [local]
  (re-find #"\.\." local))

(defn split-local-domain [email]
  (str/split email #"@"))

(defn alphanumeric? [s]
  (boolean (re-find #"^[a-zA-Z0-9.-]+$" s)))

(defn numeric-tld? [tld]
  (not (boolean (re-find #"[a-zA-Z]" tld))))

(defn validate-email? [email]
  (let [email-parts (split-local-domain email)
        errors (atom [])
        check (fn [pred? error-msg] (when pred? (swap! errors conj error-msg)))]

    (check (not (= 2 (count email-parts))) "Missing or too many @")
    (check (consecutive-dots? email) "Consecutive .")

    (when (= 2 (count email-parts))
      (check (some #(or
                     (starts-or-ends-with? % "-")
                     (starts-or-ends-with? % "."))
                   email-parts) "- or . found at the beginning or end of parts of email")
      (check (some #(not (alphanumeric? %)) email-parts) "parts of the email are not alphanumeric")
      (let [domain-parts (str/split (second email-parts) #"\.")
            not-valid-domain? (or
                               (not (<= 2 (count domain-parts)))
                               (some str/blank? domain-parts))
            tld (when-not not-valid-domain? (last domain-parts))]
        (check not-valid-domain? "domain is missing a part")
        (when-not not-valid-domain?
          (check (numeric-tld? tld) "numeric tld"))))

    @errors))
