(ns digdir.api.http-test
  "Operator Console auth middleware — pinning the 401 wire body.

   `wrap-api-key-auth` renders its 401 per surface (#137): OpenAI's error
   object under `/v1`, the historical string body everywhere else. The
  operator API is guarded by a *different* middleware, `wrap-admin-auth`,
  and its 401 must stay exactly as it was. Nothing here exercises the #137
  change; the point is to catch it reaching further than intended."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [digdir.api.http :as http]
            [digdir.auth.core :as auth]
            [digdir.config.core :as config-core]
            [digdir.config.permissions :as perms]
            [digdir.data.db :as db])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(defn- console-401-body
  "Drive wrap-admin-auth to a 401 with no valid token, and decode the body."
  []
  (let [handler (http/wrap-admin-auth (fn [_] {:status 200 :body "OK"}))]
    (with-redefs [auth/verify-token (fn [_] {:valid false})
                  db/get-conn (fn [] (atom nil))]
      (let [response (handler {:uri "/console-api/datasets"
                               :request-method :get
                               :headers {}
                               :cookies {}})]
        (is (= 401 (:status response)))
        {:response response
         :body (json/parse-string (:body response) true)}))))

(defn- index-response
  "Drive `wrap-index-page` with `get-modules` stubbed, so the result does not
   depend on whether whoever runs the suite happens to have built a client."
  [modules extra-config]
  (let [handler (http/wrap-index-page
                  (fn [_] {:status 999 :body "fell through to next-handler"})
                  (merge {:resources-path "public"
                          :index-path "public/admin_app/index.prod.html"
                          :manifest-path "public/admin_app/js/manifest.edn"}
                         extra-config))]
    (with-redefs [http/get-modules (fn [_] modules)]
      (handler {:request-method :get :uri (or (:uri extra-config) "/admin_app/js/main.js")}))))

(deftest websocket-message-limit-is-conservative-and-configurable
  (is (= (* 4 1024 1024) http/default-websocket-max-message-bytes))
  (is (= 65536 (http/websocket-max-message-bytes
                {:websocket-max-message-bytes 65536})))
  (is (= http/default-websocket-max-message-bytes
         (http/websocket-max-message-bytes
          {:websocket-max-message-bytes -1})))
  (is (= http/default-websocket-max-message-bytes
         (http/websocket-max-message-bytes
          {:websocket-max-message-bytes "invalid"}))))

(deftest missing-client-build-answers-404-and-names-the-fix
  (testing "With no client build, a request for a client asset is a 404 that
            says so — NOT a 200 serving index HTML where JS is expected (#330)"
    ;; Measured before the fix: 200 OK, Content-Type text/html, on every
    ;; missing asset including invented filenames — so the admin UI was a blank
    ;; page with nothing in any log. The branch below already existed and was
    ;; unreachable, because `(merge config nil)` returns `config`, which is
    ;; truthy. See docs/investigations/330-dev-client-decoupling-measurement.md.
    (let [resp (index-response nil {:missing-client-message "run `bb build-client`"})]
      (is (= 404 (:status resp)))
      (is (str/includes? (str (get-in resp [:headers "Content-Type"])) "text/plain"))
      (is (str/includes? (:body resp) "missing-shadow-build-manifest")
          "the machine-readable marker survives")
      (is (str/includes? (:body resp) "run `bb build-client`")
          "and the caller's instruction reaches the developer's browser"))))

(deftest a-missing-asset-does-not-answer-with-the-index-page
  (testing "#406 — a request for an asset that does not exist must NOT be
            answered with the SPA index, even when the client build is present"
    ;; Measured on the deployed test service before this fix:
    ;;   GET /admin_app/styles.css        -> 200, 164,751 bytes  (exists)
    ;;   GET /admin_app/nope-not-real.css -> 200,   1,512 bytes  (DOES NOT EXIST)
    ;; The 1,512-byte body is the index page. So HTTP STATUS IS NOT A PRESENCE
    ;; TEST on this service — every path returns 200 — and a browser asking for
    ;; CSS receives text/html, which fails as a blank page rather than a 404.
    ;;
    ;; #330 fixed the sibling case (manifest ABSENT). This is the general one:
    ;; manifest present, asset absent.
    ;; NOTE the list below carries no `.map` or `.woff2`. The extension list is
    ;; SHARED with `wrap-admin-auth`, and adding an entry there makes that
    ;; extension fetchable WITHOUT AUTHENTICATION — a security decision, not a
    ;; tidy-up. So a missing `.map` still answers with the index for an
    ;; authenticated caller. That residual is deliberate and is recorded rather
    ;; than silently covered by a second, divergent list.
    (doseq [uri ["/admin_app/nope-not-real.css"
                 "/admin_app/js/never-built.js"
                 "/assets/missing.svg"]]
      (let [resp (index-response {"main.js" "main.ABC.js"} {:uri uri})]
        (is (= 999 (:status resp))
            (str uri " was answered by wrap-index-page instead of falling through "
                 "to the next handler, which is what produces a 200 text/html "
                 "for an asset that does not exist"))))))

(deftest a-client-route-still-reaches-the-index-page
  (testing "The fallback must survive for real SPA routes — including ones
            carrying a dot, which is why the check is an extension ALLOWLIST
            and not `contains a dot`"
    ;; A dataset or conversation identifier may contain a dot. Keying on "has a
    ;; dot" would 404 a legitimate client route, which is a worse bug than the
    ;; one being fixed.
    (doseq [uri ["/" "/conversations/42" "/datasets/public-docs"
                 "/datasets/v0.1-details" "/config/api-keys"]]
      (let [resp (index-response {"main.js" "main.ABC.js"} {:uri uri})]
        (is (= 200 (:status resp))
            (str uri " must still be answered with the index page so the SPA can route it"))))))

(deftest present-client-build-is-injected-from-the-manifest
  ;; #406 changed the URI this drives, and the reason matters: it used to request
  ;; `/admin_app/js/main.js`. That is an ASSET path, and wrap-index-page no
  ;; longer answers those with the index — which is the whole point of #406. The
  ;; URI was always incidental here; this test is about TEMPLATING, and it now
  ;; uses a client route so it exercises the templating rather than the
  ;; asset-fallthrough it never meant to assert.
  ;;
  ;; Nothing real depended on the old behaviour: in a served build `main.js`
  ;; exists and `wrap-resource` answers it long before this handler is reached.
  (testing "With a manifest, the fingerprinted bundle name is templated in"
    (let [resp (index-response {:hyperfiddle.client.module/main "/js/main.DEADBEEF.js"} {:uri "/"})]
      (is (= 200 (:status resp)))
      (is (str/includes? (str (get-in resp [:headers "Content-Type"])) "text/html"))
      (is (str/includes? (:body resp) "/admin_app/js/main.DEADBEEF.js")
          "index.prod.html injects the hashed name; this is what lets a
           prebuilt client be served with no watch running"))))

(deftest admin-index-does-not-run-mutable-third-party-javascript
  (testing "both templates pin KaTeX with SRI and load no @latest/unpkg scripts"
    (doseq [resource ["public/admin_app/index.dev.html"
                      "public/admin_app/index.prod.html"]]
      (let [html (slurp (io/resource resource))]
        (is (not (str/includes? html "@latest")) resource)
        (is (not (str/includes? html "unpkg.com")) resource)
        (is (str/includes? html "katex@0.16.11") resource)
        (is (str/includes? html "integrity=\"sha384-") resource)
        (is (str/includes? html "crossorigin=\"anonymous\"") resource)))))

(deftest system-atlas-inline-script-matches-the-csp-hash
  (let [html (slurp (io/resource "public/system-explorer.html"))
        script (second (re-find #"(?s)<script>(.*?)</script>" html))
        digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes script StandardCharsets/UTF_8))
        hash (.encodeToString (Base64/getEncoder) digest)]
    (is (= "QDLQ1qte1Ui0nbdnqKtIFRYCCnNhSxpS93AEZ6Yt16o=" hash)
        "changing the inline atlas script requires deliberately updating CSP")))

(deftest browser-security-headers-cover-all-http-responses
  (let [response (with-redefs [config-core/auth-secure-cookies? (constantly false)]
                   ((http/wrap-security-headers
                     (constantly {:status 200
                                  :headers {"Content-Type" "text/plain"}
                                  :body "ok"}))
                    {:uri "/"}))
        headers (:headers response)
        csp (get headers "Content-Security-Policy")]
    (is (= "text/plain" (get headers "Content-Type"))
        "existing response headers are preserved")
    (is (= "nosniff" (get headers "X-Content-Type-Options")))
    (is (= "DENY" (get headers "X-Frame-Options")))
    (is (= "no-referrer" (get headers "Referrer-Policy")))
    (is (str/includes? (get headers "Permissions-Policy") "camera=()"))
    (is (str/includes? csp "default-src 'self'"))
    (is (str/includes? csp "frame-ancestors 'none'"))
    (is (str/includes? csp "connect-src 'self'"))
    (is (str/includes? csp "sha384-7zkQWkzuo3B5mTepMUcHkMB5jZaolc2xDwL6VFqjFALcbeS9Ggm/Yr2r3Dy4lfFg")
        "only the SRI-pinned KaTeX bytes, not the whole CDN, are executable")
    (is (not (str/includes? csp "script-src 'self' https://")))
    (is (str/includes? csp "script-src-attr 'none'"))
    (is (str/includes? csp "sha256-QDLQ1qte1Ui0nbdnqKtIFRYCCnNhSxpS93AEZ6Yt16o=")
        "the reviewed System Atlas inline script remains executable")
    (is (not (str/includes? csp "script-src 'self' 'unsafe-inline'")))
    (is (not (str/includes? csp "unsafe-eval")))
    (is (nil? (get headers "Strict-Transport-Security"))
        "localhost HTTP must not be pinned to HTTPS")))

(deftest https-deployments-emit-hsts
  (let [response (with-redefs [config-core/auth-secure-cookies? (constantly true)]
                   ((http/wrap-security-headers (constantly {:status 204})) {}))]
    (is (= "max-age=31536000; includeSubDomains"
           (get-in response [:headers "Strict-Transport-Security"])))))

(deftest server-rendered-auth-pages-declare-html-content-type
  (testing "nosniff auth responses render as HTML instead of literal source text"
    (doseq [uri ["/auth" "/login"]]
      (let [response (http/auth-router {:request-method :get :uri uri})]
        (is (= 200 (:status response)) uri)
        (is (= "text/html; charset=utf-8"
               (get-in response [:headers "Content-Type"])) uri)
        (is (str/starts-with? (:body response) "<html>") uri)))))

(defn- auth-post-response
  [delivery]
  (with-redefs [db/get-conn (fn [] (atom nil))
                perms/can-login? (fn [_ email]
                                   {:allowed? true
                                    :user {:user/email email}})
                auth/generate-confirmation-code (constantly "123456")
                auth/deliver-confirmation-code! (constantly delivery)]
    (http/auth-router {:request-method :post
                       :uri "/auth"
                       :params {:email "developer@example.com"}})))

(deftest dev-fallback-code-is-carried-to-confirmation-page
  (testing "only the logged dev fallback places the code in the login session"
    (is (= {:pending-email "developer@example.com"
            :dev-confirmation-code "123456"}
           (:session (auth-post-response :logged))))
    (is (= {:pending-email "developer@example.com"}
           (:session (auth-post-response :sent)))))

  (testing "the armed dev gate prefills the confirmation input"
    (with-redefs [auth/dev-confirmation-code-logging? (constantly true)]
      (let [response (http/auth-router
                      {:request-method :get
                       :uri "/auth/confirm-email"
                       :session {:pending-email "developer@example.com"
                                 :dev-confirmation-code "123456"}})]
        (is (= 200 (:status response)))
        (is (str/includes? (:body response) "value=\"123456\""))
        (is (str/includes? (:body response) "data-testid=\"dev-login-notice\""))
        (is (str/includes? (:body response) "Local development mode"))
        (is (str/includes? (:body response)
                           "In production, login codes are sent by email."))))))

(deftest production-confirmation-page-never-renders-carried-code
  (testing "the render-time gate protects against a stale or injected session value"
    (with-redefs [auth/dev-confirmation-code-logging? (constantly false)]
      (let [response (http/auth-router
                      {:request-method :get
                       :uri "/auth/confirm-email"
                       :session {:pending-email "developer@example.com"
                                 :dev-confirmation-code "123456"}})]
        (is (= 200 (:status response)))
        (is (not (str/includes? (:body response) "123456")))
        (is (not (str/includes? (:body response) "dev-login-notice")))
        (is (not (str/includes? (:body response) "Local development mode")))))))

(deftest successful-login-uses-the-atomic-code-consumer
  (let [verification-calls (atom [])]
    (with-redefs [auth/verify-and-consume-confirmation-code!
                  (fn [email code]
                    (swap! verification-calls conj [email code])
                    {:status :valid})
                  perms/can-login? (fn [_ email]
                                     {:allowed? true
                                      :user {:user/id "admin-1"
                                             :user/email email}})
                  db/get-conn (fn [] (atom nil))
                  auth/create-token (fn [user-id _expiry] (str "jwt-for-" user-id))
                  auth/create-expiry (constantly 12345)
                  config-core/auth-jwt-token-expiry-hours (constantly 12)
                  config-core/auth-cookie-domain (constantly nil)
                  config-core/auth-jwt-cookie-max-age (constantly 3600)
                  config-core/auth-secure-cookies? (constantly true)]
      (let [response (http/auth-router
                      {:request-method :post
                       :uri "/auth/confirm-email"
                       :session {:pending-email "developer@example.com"}
                       :params {:confirmation-code "123456"}})]
        (is (= 302 (:status response)))
        (is (= [["developer@example.com" "123456"]] @verification-calls))
        (is (= "jwt-for-admin-1" (get-in response [:cookies "auth-token" :value])))
        (is (true? (get-in response [:cookies "auth-token" :secure])))
        (is (nil? (:session response)))))))

(deftest test-operator-api-401-body-is-unchanged
  (testing "the operator API keeps its own 401 body"
    ;; Asserted on the decoded JSON rather than the Ring map: an absent key
    ;; and a nil value are the same lookup in Clojure and different bytes
    ;; on the wire.
    (let [{:keys [body]} (console-401-body)]
      (is (string? (:error body))
          "operator clients read a string here, not an OpenAI error object")
      (is (= "Unauthorized" (:error body))))))

(deftest test-operator-api-401-carries-no-bearer-challenge
  (testing "the operator API does not advertise Bearer"
    ;; It authenticates with the auth-token cookie. A Bearer challenge here
    ;; would tell a client to retry with a token this surface never accepts
    ;; — the same reason wrap-debug-api-key-auth was left alone in #121.
    (let [{:keys [response]} (console-401-body)]
      (is (nil? (get-in response [:headers "WWW-Authenticate"]))))))
