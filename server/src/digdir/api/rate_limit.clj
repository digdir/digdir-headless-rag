(ns digdir.api.rate-limit
  "Rate limiting for the admin-login endpoints.

   ## Why this stopped inferring failure from the HTTP status (#211)

   The previous version recorded an attempt when the response status was
   `nil`, `< 200` or `>= 300`, described in its own comment as \"only failed
   login attempts\". Those two are not the same set, and on this login they
   are close to opposites:

     - a WRONG confirmation code renders the invalid-code page, which is a
       `200`, so a guess was never counted;
     - a SUCCESSFUL login is a `302` redirect, so successes were.

   Measured before the fix: 15 wrong codes produced 0 blocks, while 12
   successful `POST /auth` requests blocked after 7. The throttle protected
   the \"send me a code\" button and ignored brute force against a six-digit
   code.

   A status is the wrong signal because it describes what the browser should
   do next, not whether authentication failed. So the handler now TELLS this
   middleware, via `mark-failed-attempt`, and the middleware records what it
   is told. There is nothing left to infer.

   ## Two surfaces, two budgets

   `POST /auth` (send me a code) and `POST /auth/confirm-email` (here is my
   code) shared one `/auth*` rule. They are different attack surfaces:

   | rule | records | budgets | bounds |
   |---|---|---|---|
   | `:send-a-code` | every request | IP + email | mail bombing, and enumeration |
   | `:guess-a-code` | signalled failures | IP | brute force |

   `:send-a-code` counting EVERY request is deliberate and is not the bug
   above: that budget is about volume, not about failure. A caller who asks
   for twenty codes in fifteen minutes is abusing it whether or not the
   address exists.

   The per-email budget stops one address being mailed repeatedly. It is
   sound without email normalisation, which this codebase does not do
   anywhere on the auth path: `can-login?` looks up `:user/email` by exact
   match, so `User@x` does not resolve to `user@x` and no mail is sent for a
   case variant. Verified against the database rather than assumed. The
   budget is therefore keyed on the address exactly as `can-login?` receives
   it — budget and send-decision key on the same string, so they cannot
   diverge.

   The per-IP budget is the one that bounds ENUMERATION, and only it can: a
   per-email budget gives every probed address a fresh budget, which is no
   bound at all when the attacker's goal is to learn which addresses exist.
   `POST /auth` distinguishes approved, pending and unknown addresses to an
   unauthenticated caller, so that is worth bounding.

   ## Where the counters live

   Still a process-global atom, deliberately, and unlike #63 that is now
   defensible. The real defence against code guessing is the per-code failure
   budget in `digdir.auth.core`, which lives in the database with the code and
   survives a restart. What is in memory here is a coarse source heuristic —
   already approximate under NAT and rotating addresses — whose loss on
   restart no longer removes the guarantee. Persisting a counter per request
   would put a write on every auth request to sharpen a heuristic."
  (:require
   [clojure.tools.logging :as log]
   [hiccup2.core :as h]
   [ring.util.response :as res]
   [digdir.api.util :as api-util]
   [digdir.config.core :as config-core]))

;; Rate limiting state: {bucket-key {:attempts count :last-reset timestamp}}
(def rate-limit-store (atom {}))

(def ^:private window-ms (* 15 60 1000))

(def failed-attempt-key
  "Response key a handler sets to say \"this was an authentication failure\".

   The handler knows; the status does not. See the namespace docstring."
  ::failed-attempt)

(defn mark-failed-attempt
  "Mark a response as an authentication failure so the limiter records it.

   Use this rather than assoc'ing the key directly — the whole point of #211
   is that the signal has one definition."
  [response]
  (assoc response failed-attempt-key true))

(def ^:private auth-rate-rules
  "One entry per auth surface. `:budgets` is bucket -> attempts per window."
  [{:id :guess-a-code
    :uri "/auth/confirm-email"
    :records :signalled-failures
    :budgets {:ip 10}}
   {:id :send-a-code
    :uri "/auth"
    :records :every-request
    :budgets {:ip 10 :email 5}}])

(defn- cleanup-old-entries!
  "Remove entries older than 1 hour from the rate limit store"
  []
  (let [one-hour-ago (- (System/currentTimeMillis) (* 60 60 1000))]
    (swap! rate-limit-store
           (fn [store]
             (into {} (filter (fn [[_ v]] (> (:last-reset v) one-hour-ago)) store))))))

(defn get-client-ip
  "Extract client IP address from request, handling proxied requests"
  [request]
  (or (when (config-core/rate-limit-trust-x-forwarded-for?)
        (get-in request [:headers "x-forwarded-for"]))
      (:remote-addr request)
      "unknown"))

(defn- request-email
  "The email a request is about, read the same way the handler reads it."
  [request]
  (or (api-util/param-value (:params request) :email)
      (api-util/param-value (:form-params request) :email)
      (get-in request [:session :pending-email])))

(defn- rule-for
  "The rule governing this request, or nil when it governs none."
  [request]
  (when (= :post (:request-method request))
    (first (filter #(= (:uri request) (:uri %)) auth-rate-rules))))

(defn- buckets
  "[[bucket-key limit] ...] for a request under a rule.

   A bucket whose value is missing is skipped rather than lumped under a
   shared placeholder — bucketing every anonymous caller together would let
   one of them lock out the rest."
  [rule request]
  (keep (fn [[bucket limit]]
          (when-let [v (case bucket
                         :ip (get-client-ip request)
                         :email (request-email request))]
            [(str (name (:id rule)) "|" (name bucket) "|" v) limit]))
        (:budgets rule)))

(defn- over-budget?
  "True when any of this request's buckets is out of attempts.

   Expired windows are dropped as they are encountered, which is what makes
   the window rolling per bucket rather than global."
  [bucket-limits]
  (let [cutoff (- (System/currentTimeMillis) window-ms)]
    (boolean
      (some (fn [[k limit]]
              (when-let [entry (get @rate-limit-store k)]
                (if (< (:last-reset entry) cutoff)
                  (do (swap! rate-limit-store dissoc k) false)
                  (>= (:attempts entry) limit))))
            bucket-limits))))

(defn- record-attempt!
  "Record one attempt against every bucket this request belongs to."
  [bucket-limits]
  (let [now (System/currentTimeMillis)
        cutoff (- now window-ms)]
    (swap! rate-limit-store
           (fn [store]
             (reduce (fn [acc [k _limit]]
                       (let [entry (get acc k)]
                         (if (or (nil? entry) (< (:last-reset entry) cutoff))
                           (assoc acc k {:attempts 1 :last-reset now})
                           (update-in acc [k :attempts] inc))))
                     store
                     bucket-limits)))))

(defn- too-many-attempts-response []
  (-> (res/response (str (h/html
                          [:html
                           [:head
                            [:meta {:charset "utf-8"}]
                            [:title "For mange forsøk"]
                            [:link {:rel "stylesheet"
                                    :href "/admin_app/styles.css"}]
                            [:link {:rel "icon"
                                    :type "image/svg+xml"
                                    :href "digdir_icon.svg"}]]
                           [:body {:class "flex justify-center items-center min-h-screen bg-[#F2F4F7]"}
                            [:div {:class "w-[450px] p-9 bg-white flex flex-col gap-6 rounded-lg shadow-sm"}
                             [:div {:class "flex flex-col gap-2"}
                              [:h1 {:class "text-2xl font-semibold text-[#0D1B2A]"} "For mange forsøk"]
                              [:div {:class "flex items-start gap-3 p-4 bg-[#FCF2E2] border border-[#AD7214] rounded-lg"}
                               [:svg {:class "w-5 h-5 text-[#AD7214] flex-shrink-0 mt-0.5"
                                      :xmlns "http://www.w3.org/2000/svg"
                                      :viewBox "0 0 20 20"
                                      :fill "currentColor"}
                                [:path {:fill-rule "evenodd"
                                        :d "M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 5a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 5zm0 9a1 1 0 100-2 1 1 0 000 2z"
                                        :clip-rule "evenodd"}]]
                               [:p {:class "text-sm text-[#3C2807] leading-relaxed"}
                                "Du har forsøkt å logge inn for mange ganger. "
                                "Vennligst vent 15 minutter før du prøver igjen."]]]
                             [:div {:class "flex flex-col gap-4"}
                              [:a {:href "/login"
                                   :class "px-6 py-3 bg-[#0D1B2A] text-white rounded-lg hover:bg-[#1B2D3F] transition-colors font-medium text-center"}
                               "Tilbake til innlogging"]]]]])))
      (res/status 429)))

(defn wrap-rate-limit
  "Rate limiting middleware for the admin-login endpoints.

   Records what the handler reports, never what the status implies — see the
   namespace docstring for the defect that distinction fixes (#211)."
  [handler]
  (fn [request]
    ;; Periodically clean up old entries (every 100 requests)
    (when (zero? (mod (rand-int 100) 100))
      (cleanup-old-entries!))

    (if-let [rule (rule-for request)]
      (let [bucket-limits (buckets rule request)]
        (if (over-budget? bucket-limits)
          (do
            (log/warn (str "Rate limit exceeded on " (:uri request)
                           " (" (name (:id rule)) ") for IP: " (get-client-ip request)))
            (too-many-attempts-response))
          (let [response (handler request)
                record? (case (:records rule)
                          :every-request true
                          :signalled-failures (boolean (get response failed-attempt-key)))]
            (when record?
              (log/debug (str "Recording attempt on " (:uri request)
                              " (" (name (:id rule)) ") for IP: " (get-client-ip request)))
              (record-attempt! bucket-limits))
            (dissoc response failed-attempt-key))))
      (handler request))))
