(ns digdir.api.rate-limit
  "Rate limiting utilities to prevent brute force attacks on authentication endpoints."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [hiccup2.core :as h]
   [ring.util.response :as res]
   [digdir.config.accessor :as cfg]))

;; Rate limiting state: {ip-address {:attempts count :last-reset timestamp}}
(def rate-limit-store (atom {}))

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
  (or (when (cfg/get :services :rate-limiting :trust-x-forwarded-for)
        (get-in request [:headers "x-forwarded-for"]))
      (:remote-addr request)
      "unknown"))

(defn- rate-limited?
  "Check if an IP address has exceeded rate limits for auth endpoints.
  Limits: 10 attempts per 15 minutes per IP address"
  [ip-address]
  (let [current-time (System/currentTimeMillis)
        fifteen-minutes-ago (- current-time (* 15 60 1000))
        entry (get @rate-limit-store ip-address)]
    (when entry
      (if (< (:last-reset entry) fifteen-minutes-ago)
        ;; Reset if outside the window
        (do
          (swap! rate-limit-store dissoc ip-address)
          false)
        ;; Check if exceeded limit
        (>= (:attempts entry) 10)))))

(defn- record-attempt!
  "Record an authentication attempt for rate limiting"
  [ip-address]
  (let [current-time (System/currentTimeMillis)
        fifteen-minutes-ago (- current-time (* 15 60 1000))]
    (swap! rate-limit-store
           (fn [store]
             (let [entry (get store ip-address)
                   should-reset? (or (nil? entry)
                                     (< (:last-reset entry) fifteen-minutes-ago))]
               (if should-reset?
                 (assoc store ip-address {:attempts 1 :last-reset current-time})
                 (update-in store [ip-address :attempts] inc)))))))

(defn wrap-rate-limit
  "Rate limiting middleware for authentication endpoints to prevent brute force attacks.
  Only counts failed login attempts (non-2xx responses) to avoid blocking legitimate users."
  [handler]
  (fn [request]
    (let [uri (:uri request)
          auth-endpoint? (or (= uri "/auth")
                             (str/starts-with? uri "/auth/"))]
      ;; Periodically clean up old entries (every 100 requests)
      (when (zero? (mod (rand-int 100) 100))
        (cleanup-old-entries!))

      (if (and auth-endpoint? (= (:request-method request) :post))
        (let [client-ip (get-client-ip request)]
          (if (rate-limited? client-ip)
            (do
              (log/warn (str "Rate limit exceeded for IP: " client-ip " on endpoint: " uri))
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
            ;; Process the request first, then record failed attempts
            (let [response (handler request)
                  status (:status response)]
              ;; Only record failed login attempts (non-2xx responses)
              (when (or (nil? status) (< status 200) (>= status 300))
                (log/debug (str "Recording failed login attempt for IP: " client-ip " (status: " status ")"))
                (record-attempt! client-ip))
              response)))
        ;; Not an auth endpoint POST request, pass through
        (handler request)))))
