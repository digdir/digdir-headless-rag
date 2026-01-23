(ns digdir.api.http
  "Electric integrated into a sample ring + jetty app."
  (:require
   [digdir.auth.core :as auth]
   [digdir.auth.cookies :as auth-cookies]
   [digdir.auth.views :as views]
   [digdir.api.routes :as api]
   [digdir.api.rate-limit :as rate-limit]
   [digdir.config.permissions :as perms]
   [digdir.data.db :as db]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [contrib.assert :refer [check]]
   [hyperfiddle.electric-ring-adapter3 :as electric-ring]
   [reitit.ring :as reitit-ring]
   [ring.adapter.jetty :as ring]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.cookies :as cookies]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.resource :refer [wrap-resource]]
   [ring.middleware.session :refer [wrap-session]]
   [ring.middleware.session.cookie :refer [cookie-store]]
   [ring.util.response :as res]
   [digdir.config.accessor :as cfg])
  (:import
   (org.eclipse.jetty.server.handler.gzip GzipHandler)
   (org.eclipse.jetty.websocket.server.config JettyWebSocketServletContainerInitializer JettyWebSocketServletContainerInitializer$Configurator)))

;; Auth route handlers
(defn- health-check-handler [_] (res/response "ok"))

(defn- auth-post-handler [ring-req]
  (let [;; Check both :params and :form-params, with both string and keyword keys
        email (or (get-in ring-req [:params "email"])
                  (get-in ring-req [:params :email])
                  (get-in ring-req [:form-params "email"])
                  (get-in ring-req [:form-params :email]))]
    (if email
      (let [db @(db/get-conn)
            login-result (perms/can-login? db email)]
        (if (:allowed? login-result)
          (do
            (auth/send-confirmation-code email (auth/generate-confirmation-code email))
            (-> (res/redirect "/auth/confirm-email")
                (assoc :session {:pending-email email})))
          ;; Not allowed - show appropriate error
          (-> (res/redirect "/not-approved")
              (assoc :session {:pending-email email
                               :error-reason (:reason login-result)}))))
      (res/status (views/error-page "Error" "Email address is required.") 400))))

(defn- not-approved-handler [ring-req]
  (let [email (get-in ring-req [:session :pending-email])
        error-reason (get-in ring-req [:session :error-reason])]
    (views/not-approved-page email error-reason)))

(defn- logout-handler [_]
  (auth-cookies/remove-http-only-cookie (res/redirect "/")))

(defn- confirm-email-get-handler [ring-req]
  (let [email (get-in ring-req [:session :pending-email])]
    (if email
      (views/confirm-email-page email)
      (res/status (views/error-page "Error" "Email address is missing.") 400))))

(defn- confirm-email-post-handler [ring-req]
  (let [email (get-in ring-req [:session :pending-email])
        ;; Check both :params and :form-params, with both string and keyword keys
        confirmation-code (or (get-in ring-req [:params "confirmation-code"])
                              (get-in ring-req [:params :confirmation-code])
                              (get-in ring-req [:form-params "confirmation-code"])
                              (get-in ring-req [:form-params :confirmation-code]))]
    (if (and email confirmation-code)
      (if (auth/valid-code? email confirmation-code)
        (let [db @(db/get-conn)
              login-result (perms/can-login? db email)
              user (:user login-result)]
          (if (:allowed? login-result)
            (let [jwt-token (auth/create-token (:user/id user) (auth/create-expiry {:multiplier (cfg/get :services :auth :jwt-token-expiry-hours)
                                                                                    :timespan :hours}))
                  response (res/redirect "/")]
              (swap! auth/confirmation-codes dissoc email)
              (-> response
                  (auth-cookies/set-http-only-cookie jwt-token)
                  (assoc :session nil)))
            ;; No permissions - deny access
            (-> (res/response (str "Access denied: " (:reason login-result)))
                (res/status 403)
                (res/content-type "text/plain"))))
        (views/invalid-code-page))
      (res/status (views/error-page "Error" "Missing email or confirmation code.") 400))))

(def auth-routes
  "Auth route definitions for admin login (public, no authentication required)"
  [["/up" {:get {:handler health-check-handler}}]
   ["/auth" {:get {:handler (fn [_] (views/email-signup))}
             :post {:handler auth-post-handler}}]
   ["/not-approved" {:get {:handler not-approved-handler}}]
   ["/login" {:get {:handler (fn [_] (views/login))}}]
   ["/logout" {:get {:handler logout-handler}}]
   ["/auth/confirm-email" {:get {:handler confirm-email-get-handler}
                           :post {:handler confirm-email-post-handler}}]])

(def auth-router
  "Ring handler for auth routes"
  (reitit-ring/ring-handler
   (reitit-ring/router auth-routes)
   (fn [_] nil)))

(defn wrap-admin-auth
  "Authentication middleware - checks JWT token and verifies user has permissions.
   Redirects to /auth for unauthenticated users instead of returning 401.
   Also handles /api/keys, /api/users, and /api/permissions routes which require admin-level JWT authentication."
  [next-handler]
  (fn [ring-req]
    (let [auth-token (get-in ring-req [:cookies "auth-token" :value])
          verified (auth/verify-token auth-token)
          valid-token? (:valid verified)
          uri (:uri ring-req)
          api-keys-route? (str/starts-with? uri "/api/keys")
          admin-route? (or (str/starts-with? uri "/api/users")
                           (str/starts-with? uri "/api/permissions"))
          static-url? (re-find #"\.(css|js|png|jpg|jpeg|gif|ico|svg)$" uri)
          auth-response (auth-router ring-req)
          db @(db/get-conn)]
      (cond
        static-url? (next-handler ring-req)  ; Allow static files
        ;; API keys routes require JWT authentication (admin only)
        (and api-keys-route? valid-token?)
        (let [user-id (:user-id verified)
              user (auth/user-by-id user-id)
              user-email (:user/email user)
              is-admin? (perms/is-admin? db user-id)]
          (if is-admin?
            (api/api-keys-router (assoc ring-req :user/id user-id :user/email user-email))
            (-> (res/response "{\"error\": \"Admin access required\"}")
                (res/status 403)
                (res/content-type "application/json"))))
        (and api-keys-route? (not valid-token?))
        (-> (res/response "{\"error\": \"Unauthorized\"}")
            (res/status 401)
            (res/content-type "application/json"))
        ;; Admin routes (/api/users, /api/permissions) require JWT authentication (admin only)
        (and admin-route? valid-token?)
        (let [user-id (:user-id verified)
              user (auth/user-by-id user-id)
              user-email (:user/email user)
              is-admin? (perms/is-admin? db user-id)]
          (if is-admin?
            (api/admin-router (assoc ring-req :user/id user-id :user/email user-email))
            (-> (res/response "{\"error\": \"Admin access required\"}")
                (res/status 403)
                (res/content-type "application/json"))))
        (and admin-route? (not valid-token?))
        (-> (res/response "{\"error\": \"Unauthorized\"}")
            (res/status 401)
            (res/content-type "application/json"))
        ;; Public auth routes (login, signup, health check, etc.)
        (some? auth-response) auth-response
        ;; Authenticated users with permissions can access the app
        valid-token? (let [user-id (:user-id verified)
                          user (auth/user-by-id user-id)
                          user-email (:user/email user)
                          has-permissions? (perms/user-has-any-permission? db user-id)]
                      (if has-permissions?
                        (next-handler (assoc ring-req :user/email user-email :user/id user-id))
                        ;; No permissions - clear the auth cookie and redirect to login
                        (auth-cookies/remove-http-only-cookie (res/redirect "/auth"))))
        ;; Unauthenticated users get redirected to auth
        :else (res/redirect "/auth")))))

;;; Electric integration

(defn electric-websocket-middleware
  "Open a websocket and boot an Electric server program defined by `entrypoint`.
  Takes:
  - a ring handler `next-handler` to call if the request is not a websocket upgrade (e.g. the next middleware in the chain),
  - a `config` map eventually containing {:hyperfiddle.electric/user-version <version>} to ensure client and server share the same version,
    - see `hyperfiddle.electric-ring-adapter/wrap-reject-stale-client`
  - an Electric `entrypoint`: a function (fn [ring-request] (e/boot-server {} my-ns/My-e-defn ring-request))
  "
  [next-handler config entrypoint]
  ;; Applied bottom-up
  (let [session-secret @auth/secret
        ;; Ensure the key is exactly 16 bytes for AES encryption
        session-key (take 16 (concat (.getBytes session-secret) (repeat 0)))]
    (-> (electric-ring/wrap-electric-websocket next-handler entrypoint) ; 8. connect electric client
        (wrap-admin-auth) ; 7. admin authentication middleware (after session, before Electric starts)
        (rate-limit/wrap-rate-limit) ; 6. rate limiting for auth endpoints
        (wrap-session {:store (cookie-store {:key (byte-array session-key)})
                       :cookie-name "ring-session"
                       :cookie-attrs {:http-only true
                                      :same-site :strict
                                      :secure (or (cfg/get :services :auth :secure-cookies?) false)
                                      :max-age (or (cfg/get :services :auth :session-max-age) 3600)}}) ; 5. session for auth flow
        (electric-ring/wrap-reject-stale-client config) ; 4. reject stale electric client
        (cookies/wrap-cookies) ; 3. makes cookies available to auth and Electric app
        (wrap-params)))) ; 1. parse query params

(defn get-modules [manifest-path]
  (when-let [manifest (io/resource manifest-path)]
    (let [manifest-folder (when-let [folder-name (second (rseq (str/split manifest-path #"\/")))]
                            (str "/" folder-name "/"))]
      (->> (slurp manifest)
        (edn/read-string)
        (reduce (fn [r module] (assoc r (keyword "hyperfiddle.client.module" (name (:name module)))
                                 (str manifest-folder (:output-name module)))) {})))))

(defn template
  "In string template `<div>$:foo/bar$</div>`, replace all instances of $key$
with target specified by map `m`. Target values are coerced to string with `str`.
  E.g. (template \"<div>$:foo$</div>\" {:foo 1}) => \"<div>1</div>\" - 1 is coerced to string."
  [t m] (reduce-kv (fn [acc k v] (str/replace acc (str "$" k "$") (str v))) t m))

;;; Template and serve index.html

(defn wrap-index-page
  "Server the index HTML file with injected javascript modules from `manifest.edn`.
`manifest.edn` is generated by the client build and contains javascript modules
information. Uses `:index-path` from config if provided, otherwise defaults to
`<resources-path>/index.html`."
  [next-handler config]
  (fn [ring-req]
    (let [index-path (or (:index-path config)
                         (str (check string? (:resources-path config)) "/index.html"))]
      (if-let [response (res/resource-response index-path)]
        (if-let [bag (merge config (get-modules (check string? (:manifest-path config))))]
          (-> (res/response (template (slurp (:body response)) bag)) ; TODO cache in prod mode
            (res/content-type "text/html") ; ensure `index.html` is not cached
            (res/header "Cache-Control" "no-store")
            (res/header "Last-Modified" (get-in response [:headers "Last-Modified"])))
          (-> (res/not-found (pr-str ::missing-shadow-build-manifest)) ; can't inject js modules
            (res/content-type "text/plain")))
        ;; index.html file not found on classpath
        (next-handler ring-req)))))

(defn not-found-handler [_ring-request]
  (-> (res/not-found "Not found")
    (res/content-type "text/plain")))

(defn wrap-api-routes
  "Middleware to intercept /api/* requests (except /api/keys) and route to API handler with API key auth"
  [next-handler]
  (let [api-handler (-> api/api-router
                        api/wrap-api-key-auth
                        wrap-params)]
    (fn [ring-req]
      (let [uri (:uri ring-req)]
        (if (and (str/starts-with? uri "/api/")
                 (not (str/starts-with? uri "/api/keys"))) ; /api/keys uses JWT auth, not API key auth
          ;; Apply API key auth middleware and route to API handler
          (api-handler ring-req)
          ;; Otherwise pass to next handler
          (next-handler ring-req))))))

(defn http-middleware [config]
  ;; these compose as functions, so are applied bottom up
  (let [session-secret @auth/secret
        session-key (take 16 (concat (.getBytes session-secret) (repeat 0)))]
    (-> not-found-handler
        (wrap-index-page config) ; 8. otherwise fallback to default page file
        (wrap-resource (:resources-path config)) ; 7. serve static file from classpath
        (wrap-resource "public") ; 6. For serving Designsystemet from the classpath
        (wrap-content-type) ; 5. detect content (e.g. for index.html)
        (wrap-admin-auth) ; 4. admin authentication - protects all HTTP routes
        (wrap-session {:store (cookie-store {:key (byte-array session-key)})
                       :cookie-name "ring-session"
                       :cookie-attrs {:http-only true
                                      :same-site :strict
                                      :secure (or (cfg/get :services :auth :secure-cookies?) false)
                                      :max-age (or (cfg/get :services :auth :session-max-age) 3600)}}) ; 3. session for auth flow
        (cookies/wrap-cookies) ; 2. parse cookies (required for wrap-admin-auth)
        (wrap-params))))

(defn middleware [config entrypoint]
  (-> (http-middleware config)  ; 3. otherwise, serve regular http content
    (electric-websocket-middleware config entrypoint) ; 2. intercept websocket upgrades and maybe start Electric
    (wrap-api-routes)))  ; 1. intercept /api/* requests (except /api/keys) for API key auth

(defn- add-gzip-handler!
  "Makes Jetty server compress responses. Optional but recommended."
  [server]
  (.setHandler server
    (doto (GzipHandler.)
      #_(.setIncludedMimeTypes (into-array ["text/css" "text/plain" "text/javascript" "application/javascript" "application/json" "image/svg+xml"])) ; only compress these
      (.setMinGzipSize 1024)
      (.setHandler (.getHandler server)))))

(defn- configure-websocket!
  "Tune Jetty Websocket config for Electric compat." [server]
  (JettyWebSocketServletContainerInitializer/configure
    (.getHandler server)
    (reify JettyWebSocketServletContainerInitializer$Configurator
      (accept [_this _servletContext wsContainer]
        (.setIdleTimeout wsContainer (java.time.Duration/ofSeconds 60))
        (.setMaxBinaryMessageSize wsContainer (* 100 1024 1024)) ; 100M - temporary
        (.setMaxTextMessageSize wsContainer (* 100 1024 1024))   ; 100M - temporary
        ))))

(defn start-server! [entrypoint
                     {:keys [port host]
                      :or   {port 8080, host "0.0.0.0"}
                      :as   config}]
  (let [server     (ring/run-jetty (middleware config entrypoint)
                     (merge {:port         port
                             :join?        false
                             :configurator (fn [server]
                                             (configure-websocket! server)
                                             (add-gzip-handler! server))}
                       config))]
    (log/info "👉" (str "http://" host ":" (-> server (.getConnectors) first (.getPort))))
    server))
