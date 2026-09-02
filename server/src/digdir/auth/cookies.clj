(ns digdir.auth.cookies
  "Cookie management utilities for HTTP-only secure cookies."
  (:require [digdir.config.core :as config-core]))

(defn set-http-only-cookie
  "Helper function to set an HTTP-only cookie with the JWT token."
  [response jwt-token]
  (let [cookie-domain (config-core/auth-cookie-domain)
        max-age (config-core/auth-jwt-cookie-max-age)
        secure? (config-core/auth-secure-cookies?)]
    (assoc-in response [:cookies "auth-token"]
              (cond-> {:value jwt-token
                       :path "/"
                       :http-only true
                       :secure secure?
                       :same-site :lax
                       :max-age max-age}
                cookie-domain (assoc :domain cookie-domain)))))

(defn remove-http-only-cookie
  "Remove the HTTP-only auth cookie by setting max-age to 0."
  [response]
  (let [cookie-domain (config-core/auth-cookie-domain)
        secure? (config-core/auth-secure-cookies?)]
    (assoc-in response [:cookies "auth-token"]
              (cond-> {:value ""
                       :path "/"
                       :http-only true
                       :secure secure?
                       :same-site :lax
                       :max-age 0}
                cookie-domain (assoc :domain cookie-domain)))))
