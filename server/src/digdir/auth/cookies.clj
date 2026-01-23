(ns digdir.auth.cookies
  "Cookie management utilities for HTTP-only secure cookies."
  (:require [digdir.config.accessor :as cfg]))

(def ^:private default-cookie-max-age
  "Default cookie max-age: 7 days in seconds"
  (* 7 24 60 60))

(defn set-http-only-cookie
  "Helper function to set an HTTP-only cookie with the JWT token."
  [response jwt-token]
  (let [cookie-domain (cfg/get :services :auth :cookie-domain)
        max-age (or (cfg/get :services :auth :jwt-cookie-max-age) default-cookie-max-age)]
    (assoc-in response [:cookies "auth-token"]
              (cond-> {:value jwt-token
                       :path "/"
                       :http-only true
                       :same-site :lax
                       :max-age max-age}
                cookie-domain (assoc :domain cookie-domain)))))

(defn remove-http-only-cookie
  "Remove the HTTP-only auth cookie by setting max-age to 0."
  [response]
  (let [cookie-domain (cfg/get :services :auth :cookie-domain)]
    (assoc-in response [:cookies "auth-token"]
              (cond-> {:value ""
                       :path "/"
                       :http-only true
                       :same-site :lax
                       :max-age 0}
                cookie-domain (assoc :domain cookie-domain)))))
