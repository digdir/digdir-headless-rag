(ns digdir.api.http
  "Electric integrated into a sample ring + jetty app."
  (:require
   [digdir.auth.core :as auth]
   [digdir.auth.cookies :as auth-cookies]
   [digdir.auth.views :as views]
   [digdir.api.routes :as api]
   [digdir.api.rate-limit :as rate-limit]
   [digdir.api.util :as api-util]
   [digdir.config.core :as config-core]
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
   [ring.util.response :as res])
  (:import
   (org.eclipse.jetty.server.handler.gzip GzipHandler)))

;; Auth route handlers
(defn- health-check-handler [_] (res/response "ok"))

(defn- request-param
  [ring-req k]
  (or (api-util/param-value (:params ring-req) k)
      (api-util/param-value (:form-params ring-req) k)))

(defn- auth-post-handler [ring-req]
  (let [email (request-param ring-req :email)]
    (if email
      (let [db @(db/get-conn)
            login-result (perms/can-login? db email)]
        (if (:allowed? login-result)
          (let [confirmation-code (auth/generate-confirmation-code email)
                delivery (auth/deliver-confirmation-code!
                          config-core/global-tenant email confirmation-code)]
            (-> (res/redirect "/auth/confirm-email")
                (assoc :session
                       (cond-> {:pending-email email}
                         (= :logged delivery)
                         (assoc :dev-confirmation-code confirmation-code)))))
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
  (let [email (get-in ring-req [:session :pending-email])
        ;; `auth-post-handler` adds this value only when the dev delivery
        ;; fallback returned :logged. Check the process-global gate again
        ;; before reflecting anything into HTML, so a carried session value
        ;; can never make production render a login code.
        dev-confirmation-code (when (auth/dev-confirmation-code-logging?)
                                (get-in ring-req [:session :dev-confirmation-code]))]
    (if email
      (views/confirm-email-page email dev-confirmation-code)
      (res/status (views/error-page "Error" "Email address is missing.") 400))))

(defn- verify-code-once
  "Atomically verify/consume a code, with a wrong guess counted and the reason logged.

   The caller gets one page for every non-valid status — see
   `views/invalid-code-page`. Distinguishing `expired` from `mismatch` in the
   RESPONSE would also tell someone guessing codes whether an address has a
   login in flight, so the distinction goes to the server log, where an
   operator can answer \"why did this user's code stop working\" without
   widening what an unauthenticated caller learns."
  [email confirmation-code]
  (let [{:keys [status attempts destroyed?] :as result}
        (auth/verify-and-consume-confirmation-code! email confirmation-code)]
    (when (and (= :mismatch status) destroyed?)
      ;; A wrong guess against a code that exists. This is the event the
      ;; per-code budget counts, and destroying the code after
      ;; auth/max-code-attempts of them is what caps a brute-force run
      ;; regardless of how many source addresses it comes from (#211).
      (log/warn (str "Confirmation code for " email " destroyed after "
                     attempts " wrong guesses; a new code must be requested")))
    (when (not= :valid status)
      (log/info (str "Confirmation code rejected for " email ": " (name status)
                     (case status
                       :expired " (a code was outstanding and its window passed)"
                       :mismatch " (a code is outstanding; this is not it)"
                       :unknown " (no code on record - never requested, already used, or swept)"
                       ""))))
    result))

(defn- confirm-email-post-handler [ring-req]
  (let [email (get-in ring-req [:session :pending-email])
        confirmation-code (request-param ring-req :confirmation-code)]
    (if (and email confirmation-code)
      (if (= :valid (:status (verify-code-once email confirmation-code)))
        (let [db @(db/get-conn)
              login-result (perms/can-login? db email)
              user (:user login-result)]
          (if (:allowed? login-result)
            (let [jwt-token (auth/create-token (:user/id user) (auth/create-expiry {:multiplier (config-core/auth-jwt-token-expiry-hours)
                                                                                    :timespan :hours}))
                  response (res/redirect "/")]
              (-> response
                  (auth-cookies/set-http-only-cookie jwt-token)
                  (assoc :session nil)))
            ;; No permissions - deny access
            (-> (res/response (str "Access denied: " (:reason login-result)))
                (res/status 403)
                (res/content-type "text/plain"))))
        ;; Tell the limiter this was an authentication failure. It used to
        ;; infer that from the status, and this page is a 200, so a wrong
        ;; code was never counted (#211).
        (rate-limit/mark-failed-attempt (views/invalid-code-page)))
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

(def static-asset-uri?
  "Does this URI name a static asset rather than a client route?

   ⚠️ ONE LIST, TWO CONSUMERS, AND THEY MUST NOT DIVERGE:
     - `wrap-admin-auth` serves these WITHOUT AUTHENTICATION, so a browser can
       fetch CSS and JS before login.
     - `wrap-index-page` refuses to answer these with the SPA index, so a missing
       asset is a 404 rather than 200 text/html (#406).

   A second copy of this regex would be two places that must agree with nothing
   making them: someone adds `.webp` to the auth exemption a year from now,
   never learns the other exists, and a missing `.webp` silently returns the
   index page again — the same defect back, through a door nobody watches.

   ⚠️ EXTENDING THIS LIST IS A SECURITY DECISION, NOT A TIDY-UP. Every extension
   added here becomes fetchable WITHOUT AUTHENTICATION. That is the right trade
   for real client assets and it is the reason the list is short and explicit
   rather than a general \"has a file extension\" test.

   This warning is here rather than only in the commit that created it because of
   WHEN it will next be read: by someone whose font is 404ing, adding `.woff2` to
   make it load. At that moment the auth consequence is the least visible thing
   about this list, and the change looks like a one-word fix.

   It is an ALLOWLIST rather than \"contains a dot\" on purpose: a dataset or
   conversation identifier may carry a dot (`/datasets/v0.1-details`), and
   404ing a legitimate client route would be a worse bug than the one #406 fixes."
  (let [pattern #"\.(css|js|png|jpg|jpeg|gif|ico|svg)$"]
    (fn [uri] (boolean (re-find pattern uri)))))

(defn wrap-admin-auth
  "Authentication middleware for Operator Console routes.
   All operator routes live under /console-api/* and require a valid JWT with admin permissions.
   Non-operator requests fall through to public auth routes or the standard app handler."
  [next-handler]
  (fn [ring-req]
    (let [auth-token (get-in ring-req [:cookies "auth-token" :value])
          verified (auth/verify-token auth-token)
          valid-token? (:valid verified)
          uri (:uri ring-req)
          console-api-route? (str/starts-with? uri "/console-api/")
          static-url? (static-asset-uri? uri)
          auth-response (auth-router ring-req)
          db @(db/get-conn)]
      (cond
        static-url? (next-handler ring-req)  ; Allow static files
        ;; Operator Console routes require JWT authentication with admin permissions
        (and console-api-route? valid-token?)
        (let [user-id (:user-id verified)
              user (auth/user-by-id user-id)
              user-email (:user/email user)
              user-language (auth/user-preferred-language user)
              is-admin? (perms/is-admin? db user-id)]
          (if is-admin?
            (api/console-api-router (assoc ring-req
                                           :user/id user-id
                                           :user/email user-email
                                           :user/preferred-language user-language))
            (-> (res/response "{\"error\": \"Admin access required\"}")
                (res/status 403)
                (res/content-type "application/json"))))
        (and console-api-route? (not valid-token?))
        (-> (res/response "{\"error\": \"Unauthorized\"}")
            (res/status 401)
            (res/content-type "application/json"))
        ;; Public auth routes (login, signup, health check, etc.)
        (some? auth-response) auth-response
        ;; Authenticated users with permissions can access the app
        valid-token? (let [user-id (:user-id verified)
                           user (auth/user-by-id user-id)
                           user-email (:user/email user)
                           user-language (auth/user-preferred-language user)
                           has-permissions? (perms/user-has-any-permission? db user-id)]
                      (if has-permissions?
                        (next-handler (assoc ring-req
                                             :user/email user-email
                                             :user/id user-id
                                             :user/preferred-language user-language))
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
                                      :secure (config-core/auth-secure-cookies?)
                                      :max-age (config-core/auth-session-max-age)}}) ; 5. session for auth flow
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

(defn- read-body-as-utf8 [body]
  (with-open [input-stream (io/input-stream body)]
    (String. (.readAllBytes input-stream) java.nio.charset.StandardCharsets/UTF_8)))

(defn wrap-index-page
  "Server the index HTML file with injected javascript modules from `manifest.edn`.
`manifest.edn` is generated by the client build and contains javascript modules
information. Uses `:index-path` from config if provided, otherwise defaults to
`<resources-path>/index.html`."
  [next-handler config]
  (fn [ring-req]
    (let [index-path (or (:index-path config)
                         (str (check string? (:resources-path config)) "/index.html"))]
      (cond
        ;; #406: an asset that reached here does not exist — `wrap-resource`
        ;; would already have served it. Answering with the index gives a
        ;; browser text/html where it asked for CSS or JS, which fails as a
        ;; blank page rather than a named 404, and makes HTTP status useless as
        ;; a presence test: every path returns 200. Fall through instead.
        ;;
        ;; ⚠️ THIS MUST NOT PREEMPT THE MISSING-MANIFEST BRANCH BELOW, which is
        ;; #330's fix. That branch answers a NAMED 404 telling the developer to
        ;; run `bb build-client`, and it is reached by requests for `main.js` —
        ;; an asset path. Testing the asset extension first sent exactly that
        ;; request to `not-found-handler` and replaced the instruction with a
        ;; bare "Not found". So the manifest is checked FIRST: no client build is
        ;; a different condition from a build that lacks one file, and only the
        ;; second is a 404.
        (and (get-modules (check string? (:manifest-path config)))
             (static-asset-uri? (:uri ring-req)))
        (next-handler ring-req)

        :else
        (if-let [response (res/resource-response index-path)]
        ;; #330: test the MODULES, not `(merge config modules)`. `merge` with a
        ;; nil right-hand side returns `config`, which is a non-empty map and
        ;; therefore always truthy — so the missing-manifest branch below could
        ;; never be reached and a request for a client asset that does not exist
        ;; was answered with the index page: HTTP 200, `text/html`, where the
        ;; browser expects JavaScript. Blank page, 200 on every asset, nothing
        ;; in any log. The error channel was correct and was never fed, which is
        ;; the same shape as #303. Measured before and after in
        ;; docs/investigations/330-dev-client-decoupling-measurement.md.
        (if-let [modules (get-modules (check string? (:manifest-path config)))]
          (-> (res/response (template (read-body-as-utf8 (:body response))
                                      (merge config modules))) ; TODO cache in prod mode
            (res/content-type "text/html") ; ensure `index.html` is not cached
            (res/header "Cache-Control" "no-store")
            (res/header "Last-Modified" (get-in response [:headers "Last-Modified"])))
          ;; No client build. Say so in the response body — this is what a
          ;; developer sees in the browser, and it is the only place the
          ;; absence is visible at the moment it matters. Callers pass
          ;; `:missing-client-message` to name the command that fixes it.
          (-> (res/not-found (str (pr-str ::missing-shadow-build-manifest)
                                  "\n\n"
                                  (or (:missing-client-message config)
                                      (str "No client build found at "
                                           (:manifest-path config)
                                           ". The API is unaffected; only the "
                                           "admin UI needs this."))
                                  "\n"))
            (res/content-type "text/plain")))
        ;; index.html file not found on classpath
        (next-handler ring-req))))))

(defn not-found-handler [_ring-request]
  (-> (res/not-found "Not found")
    (res/content-type "text/plain")))

(defn wrap-api-routes
  "Middleware to intercept /api/* and /v1/* requests.
   - /api/debug/* uses debug API key auth
   - other /api/* and /v1/* use regular API key auth (X-API-Key or
     Authorization: Bearer — the latter is the OpenAI convention used
     by clients hitting /v1).
   - JWT-only operator routes are handled separately by wrap-admin-auth"
  [next-handler]
  (let [api-handler (-> api/api-router
                        api/wrap-api-key-auth
                        wrap-params)
        debug-api-handler (-> api/debug-router
                              api/wrap-debug-api-key-auth
                              wrap-params)]
    (fn [ring-req]
      (let [uri (:uri ring-req)]
        (cond
          (str/starts-with? uri "/api/debug")
          (debug-api-handler ring-req)

          (or (str/starts-with? uri "/api/")
              (str/starts-with? uri "/v1/"))
          (api-handler ring-req)

          :else
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
                                      :secure (config-core/auth-secure-cookies?)
                                      :max-age (config-core/auth-session-max-age)}}) ; 3. session for auth flow
        (cookies/wrap-cookies) ; 2. parse cookies (required for wrap-admin-auth)
        (wrap-params))))

(def ^:private content-security-policy
  (str "default-src 'self'; "
       "base-uri 'self'; "
       "object-src 'none'; "
       "frame-ancestors 'none'; "
       "form-action 'self'; "
       ;; The only third-party executable resource is the exact, SRI-pinned
       ;; KaTeX build in the index template. Electric's client and WebSocket
       ;; connection are same-origin.
       ;; The hash permits the one reviewed inline script in
       ;; public/system-explorer.html. It does not permit event handlers or
       ;; any other inline script; update it deliberately if that atlas changes.
       "script-src 'self' 'sha384-7zkQWkzuo3B5mTepMUcHkMB5jZaolc2xDwL6VFqjFALcbeS9Ggm/Yr2r3Dy4lfFg' 'sha256-QDLQ1qte1Ui0nbdnqKtIFRYCCnNhSxpS93AEZ6Yt16o='; "
       "script-src-attr 'none'; "
       ;; Electric/UI components use inline style properties. Restricting
       ;; styles to elements rather than script execution still materially
       ;; narrows the policy while keeping the existing UI functional.
       "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://altinncdn.no; "
       "font-src 'self' data: https://cdn.jsdelivr.net https://altinncdn.no; "
       "img-src 'self' data: blob:; "
       "connect-src 'self'; "
       "media-src 'self'; "
       "worker-src 'self' blob:; "
       "manifest-src 'self'"))

(defn wrap-security-headers
  "Add browser security policy to every HTTP surface, including API errors and
   the Electric upgrade response. HSTS is emitted only when secure cookies are
   enabled, which is the existing signal that the public deployment is HTTPS;
   emitting it on a developer's plain-HTTP localhost would be harmful."
  [next-handler]
  (fn [ring-req]
    (when-let [response (next-handler ring-req)]
      (let [headers (cond-> {"Content-Security-Policy" content-security-policy
                             "Permissions-Policy" "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
                             "Referrer-Policy" "no-referrer"
                             "X-Content-Type-Options" "nosniff"
                             "X-Frame-Options" "DENY"}
                      (config-core/auth-secure-cookies?)
                      (assoc "Strict-Transport-Security" "max-age=31536000; includeSubDomains"))]
        (update response :headers #(merge (or % {}) headers))))))

(defn middleware [config entrypoint]
  (-> (http-middleware config)  ; 3. otherwise, serve regular http content
    (electric-websocket-middleware config entrypoint) ; 2. intercept websocket upgrades and maybe start Electric
    (wrap-api-routes) ; 1. intercept /api/* requests for API key auth after operator routes are handled
    (wrap-security-headers))) ; apply security policy consistently to every surface

(defn- add-gzip-handler!
  "Makes Jetty server compress responses. Optional but recommended.

   text/event-stream is explicitly excluded so the MCP SSE responses
   aren't buffered into a gzip chunk before reaching the client."
  [server]
  (.setHandler server
    (doto (GzipHandler.)
      #_(.setIncludedMimeTypes (into-array ["text/css" "text/plain" "text/javascript" "application/javascript" "application/json" "image/svg+xml"])) ; only compress these
      (.setExcludedMimeTypes (into-array ["text/event-stream"]))
      (.setMinGzipSize 1024)
      (.setHandler (.getHandler server)))))

(def default-websocket-max-message-bytes (* 4 1024 1024))

(defn websocket-max-message-bytes
  "Resolve the Electric WebSocket message ceiling. An explicit server config
   value wins, followed by WEBSOCKET_MAX_MESSAGE_BYTES, then the 4 MiB safe
   default. Invalid and non-positive values fail closed to the default."
  [config]
  (let [raw (or (:websocket-max-message-bytes config)
                (System/getenv "WEBSOCKET_MAX_MESSAGE_BYTES"))]
    (try
      (let [n (if (number? raw) (long raw) (Long/parseLong (str raw)))]
        (if (pos? n) n default-websocket-max-message-bytes))
      (catch Exception _ default-websocket-max-message-bytes))))

(defn start-server! [entrypoint
                     {:keys [port host]
                      :or   {port 8080, host "0.0.0.0"}
                      :as   config}]
  (let [max-message-bytes (websocket-max-message-bytes config)
        jetty-config (dissoc config :websocket-max-message-bytes)
        server     (ring/run-jetty (middleware config entrypoint)
                     (merge {:port         port
                             :join?        false
                             ;; Electric messages carry whole transferred
                             ;; values, so keep a ceiling above Jetty's small
                             ;; default without allowing the former 100 MiB
                             ;; allocation per connection.
                             :ws-idle-timeout 60000
                             :ws-max-binary-size max-message-bytes
                             :ws-max-text-size max-message-bytes
                             :configurator (fn [server]
                                             (add-gzip-handler! server))}
                       jetty-config))]
    (log/info "👉" (str "http://" host ":" (-> server (.getConnectors) first (.getPort))))
    server))
