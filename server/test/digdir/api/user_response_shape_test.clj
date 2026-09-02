(ns digdir.api.user-response-shape-test
  "UserResponse must match what BOTH its producers emit (#193).

   The schema comment claims it is emitted identically by create-user-handler
   (201) and update-user-permissions-handler (200). That is a claim about two
   handlers, so it is checked against two handlers — #184 was filed on exactly
   this mistake, reading one handler and attributing the shape to another.

   Key sets rather than individual fields, per #191: a fabricated key is
   invisible to assertions that read only the fields they care about."
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.api.openapi-test-util :refer [keyset non-empty-properties]]
            [digdir.api.routes.conversations :as conversations]
            [digdir.auth.core :as auth]
            [digdir.config.permissions :as perms]
            [digdir.data.db :as db]))

(def ^:private pulled-user
  "What the handlers' pull query returns. Shaped as the pull spec in both
   handlers asks for, so the response is built from a faithful producer value
   rather than from a map invented for the test."
  #:user{:id "u-1"
         :email "someone@example.com"
         :created "2026-08-21T10:00:00Z"
         :permissions [#:permission{:id "admin-full" :name "Admin"}]})

(defn- create-user-body []
  (with-redefs [db/get-conn (fn [] (atom :mock-db))
                auth/create-new-user (fn [_] {:user/id "u-1"})
                auth/user-by-email (fn [_] {:user/id "u-1"})
                perms/grant-permission! (fn [_ _ _] nil)
                d/q (fn [& _] pulled-user)]
    (let [response (conversations/create-user-handler
                     {:user/id "admin-1"
                      :body-params {:email "someone@example.com"
                                    :permissions ["admin-full"]}})]
      (is (= 201 (:status response)) "create must reach its success path")
      (json/parse-string (:body response) true))))

(defn- update-permissions-body []
  (with-redefs [db/get-conn (fn [] (atom :mock-db))
                perms/grant-permission! (fn [_ _ _] nil)
                perms/revoke-permission! (fn [_ _ _] nil)
                d/q (fn [& _] pulled-user)]
    (let [response (conversations/update-user-permissions-handler
                     {:path-params {:id "u-1"}
                      :body-params {:add ["admin-full"] :remove []}})]
      (is (= 200 (:status response)) "update must reach its success path")
      (json/parse-string (:body response) true))))

(deftest user-response-matches-its-schema
  (testing "envelope"
    (let [advertised (non-empty-properties [:UserResponse :properties])]
      (doseq [[label body] [["create" (create-user-body)]
                            ["update" (update-permissions-body)]]]
        (is (= advertised (keyset body))
            (str label ": envelope key set differs. advertised " (sort advertised)
                 " actual " (sort (keyset body)))))))

  (testing "the nested user object, both directions"
    (let [advertised (non-empty-properties [:UserResponse :properties :user :properties])]
      (doseq [[label body] [["create" (create-user-body)]
                            ["update" (update-permissions-body)]]]
        (let [actual (keyset (:user body))]
          (is (empty? (set/difference advertised actual))
              (str label ": schema advertises fields the handler does not emit: "
                   (sort (set/difference advertised actual))))
          (is (empty? (set/difference actual advertised))
              (str label ": handler emits fields the schema does not advertise: "
                   (sort (set/difference actual advertised)))))))))

(deftest both-producers-emit-the-same-shape
  (testing "the schema's identical-emission claim is true of the handlers"
    ;; If these ever diverge, one schema cannot describe both and the comment
    ;; in openapi.yaml becomes false — which is the kind of claim that goes
    ;; stale silently because nothing reads it.
    (let [create (create-user-body)
          update (update-permissions-body)]
      (is (= (keyset create) (keyset update)))
      (is (= (keyset (:user create)) (keyset (:user update)))
          "create-user-handler and update-user-permissions-handler must agree"))))
