(ns digdir.auth.core
  (:require [buddy.sign.jwt :as jwt]
            [tick.core :as t]
            [clojure.string :as str]
            [clj-http.client :as http2]
            [clojure.data.json :as json]
            [nano-id.core :refer [nano-id]]
            [datahike.api :as d]
            [hiccup2.core :as h]
            [digdir.data.db :as db]
            [digdir.config.accessor :as cfg]
            [digdir.config.core :as config-core]
            [digdir.i18n :refer [t]]))

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
    (or
     ;; Try bootstrap config first (environment variable)
     (config-core/get-jwt-secret)
     ;; Fall back to runtime config from database
     (cfg/get :services :auth :jwt-secret)
     ;; Error if not found anywhere
     (throw (ex-info "JWT_SECRET must be configured" {})))))

(def confirmation-codes (atom {}))

(defn generate-confirmation-code [email]
  (let [confirmation-code (format "%06d" (rand-int 1000000))]
    (swap! confirmation-codes assoc email {:code confirmation-code
                                           :expiry (t/>> (t/now) (t/new-duration 10 :minutes))})
    confirmation-code))

(defn valid-code? [email confirmation-code]
  (let [{:keys [code expiry]} (get @confirmation-codes email)]
    (and (t/< (t/now) expiry) (= code confirmation-code))))

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

(def scaleway-region (delay (or (cfg/get :services :scaleway-tem :region) "fr-par")))
(def scaleway-project-id (delay (or (cfg/get :services :scaleway-tem :project-id)
                                    (throw (ex-info "SCW_DEFAULT_PROJECT_ID must be configured" {})))))
(def scaleway-api-key (delay (or (cfg/get :services :scaleway-tem :api-key)
                                 (throw (ex-info "SCW_SECRET_KEY must be configured" {})))))
(def scaleway-from-email (delay (or (cfg/get :services :scaleway-tem :from-email) "no-reply@digdir.cloud")))

(defn scaleway-email-url []
  (str "https://api.scaleway.com/transactional-email/v1alpha1/regions/" @scaleway-region "/emails"))

(defn send-confirmation-code
  ([to code] (send-confirmation-code @scaleway-from-email to code))
  ([from to code]
   (http2/post (scaleway-email-url)
               {:headers {"Accept" "application/json"
                          "Content-Type" "application/json"
                          "X-Auth-Token" @scaleway-api-key}
                :body (json/write-str
                       {:from {:email from}
                        :to [{:email to}]
                        :subject (t :auth/email-subject)
                        :project_id @scaleway-project-id
                        :text (str (t :auth/email-body-text code) "\n\n"
                                   (t :auth/email-support-text))
                        :html (str (h/html
                                    [:html
                                     [:body
                                      [:p (t :auth/email-body-html)]
                                      [:p {:style "font-size: 24px; font-weight: bold; color: #2D3748;"} code]
                                      [:p
                                       [:span (t :auth/email-support-html-text)]
                                       [:a {:href "mailto:hjelp@digdir.cloud"} "hjelp@digdir.cloud"]]]]))})})))

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
