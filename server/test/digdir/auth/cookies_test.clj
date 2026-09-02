(ns digdir.auth.cookies-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.auth.cookies :as cookies]
            [digdir.config.core :as config-core]))

(defn- auth-cookie
  [secure? response-fn]
  (with-redefs [config-core/auth-cookie-domain (constantly nil)
                config-core/auth-jwt-cookie-max-age (constantly 3600)
                config-core/auth-secure-cookies? (constantly secure?)]
    (get-in (response-fn) [:cookies "auth-token"])))

(deftest auth-cookie-follows-the-secure-cookie-deployment-setting
  (testing "production login and logout cookies are HTTPS-only"
    (is (true? (:secure (auth-cookie true
                                     #(cookies/set-http-only-cookie {} "jwt")))))
    (is (true? (:secure (auth-cookie true
                                     #(cookies/remove-http-only-cookie {}))))))

  (testing "plain-HTTP local development remains supported when explicitly configured"
    (is (false? (:secure (auth-cookie false
                                      #(cookies/set-http-only-cookie {} "jwt")))))
    (is (false? (:secure (auth-cookie false
                                      #(cookies/remove-http-only-cookie {})))))))

(deftest auth-cookie-retains-the-existing-browser-defences
  (let [cookie (auth-cookie true #(cookies/set-http-only-cookie {} "jwt"))]
    (is (= "jwt" (:value cookie)))
    (is (= "/" (:path cookie)))
    (is (true? (:http-only cookie)))
    (is (= :lax (:same-site cookie)))
    (is (= 3600 (:max-age cookie)))))
