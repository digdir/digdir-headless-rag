(ns digdir.api.all-tenants-marker-test
  "the WRITERS of the explicit all-tenant
   marker (`:access-policy/all-tenants?`).

   - The console route `PUT /console-api/api-keys/:key-id/all-tenants`, driven
     THROUGH `wrap-admin-auth`: each refusal names the layer that answered.
   - Every set or clear is audited with who, the old and the new value.
   - The key UI's door, `set-all-tenants-as-admin!`, is refused to a non-admin by
     the SAME predicate as the route (`perms/is-admin?`).
   - Rotate keeps the marker (the new key shares the policy).
   - The key UI's tenant label is derived from the one granted set and the marker.

   Vars that commit 4 adds are reached through `find-var`, so this namespace
   also LOADS at the base, where each such arm fails by assertion."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.api.http :as http]
            [digdir.auth.core :as auth]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.db :as config-db]
            [digdir.config.permissions :as perms]
            [digdir.config.schema :as config-schema]
            [digdir.data.db :as db]))

(defn- call
  "Call the var `sym`, or FAIL (not error) when it does not exist yet."
  [sym & args]
  (if-let [v (find-var sym)]
    (apply v args)
    (do (is (some? (find-var sym)) (str sym " does not exist")) ::missing)))

(defn- refusal [f]
  (try (f) :no-refusal (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:status :reason]))))

(def ^:private admin "admin-1")
(def ^:private reader "reader-2")
(def ^:private creator "creator-0")

(defn- make-key!
  "A key made the way the console makes one; its id is read back by validating
   it (`create-api-key!` returns only the plaintext)."
  [conn key-name opts]
  (let [plaintext (:api-key (api-keys/create-api-key! conn key-name creator (merge {:scopes #{:query}} opts)))]
    {:plaintext plaintext :id (:api-key-id (api-keys/validate-api-key conn plaintext))}))

(defn- with-store
  "A fresh store holding the admin, a permissioned non-admin, and one key made
   by a THIRD user (so any-admin, not creator-only, is what is tested)."
  [f]
  (let [cfg {:store {:backend :mem :id (str "all-tenants-marker-" (random-uuid))} :schema-flexibility :read}
        _ (d/create-database cfg)
        conn (d/connect cfg)]
    (d/transact conn {:tx-data db/dh-schema})
    (d/transact conn {:tx-data config-schema/config-migration-schema})
    (with-redefs [db/get-conn (constantly conn)
                  config-db/get-conn (constantly conn)]
      (config-db/ensure-schema! conn)
      (d/transact conn {:tx-data [{:permission/id "admin-full" :permission/name "Full admin"}
                                  {:permission/id "reader" :permission/name "Read only"}
                                  {:user/id admin :user/email "admin@example.test"}
                                  {:user/id reader :user/email "reader@example.test"}]})
      (perms/grant-permission! conn admin "admin-full")
      (perms/grant-permission! conn reader "reader")
      (let [k (make-key! conn "kt key" {:dataset-scopes [{:tenant "kt" :dataset-config-key "ds-kt"}]})]
        (try (f conn k)
             (finally (d/release conn)))))))

(defn- marked? [conn plaintext]
  (:all-tenants? (api-keys/validate-api-key conn plaintext)))

(defn- marker-audits [conn id]
  (->> (d/q '[:find [(pull ?a [:audit/action :audit/user-id :audit/previous-value :audit/new-value]) ...]
              :in $ ?id
              :where [?a :audit/api-key-id ?id] [?a :audit/action ?act]
              [(contains? #{:mark-all-tenants :unmark-all-tenants} ?act)]]
            @conn id)
       (sort-by :audit/new-value)))

(defn- put-marker
  "PUT the marker through `wrap-admin-auth`, as `user` (nil = no JWT at all),
   optionally ALSO presenting an API key the way an API client would."
  [id body & {:keys [user api-key]}]
  (let [handler (http/wrap-admin-auth (fn [_] {:status 999 :body "fell through to the app"}))]
    (with-redefs [auth/verify-token (fn [t] (if (= t "jwt") {:valid true :user-id user} {:valid false}))]
      (let [r (handler {:uri (str "/console-api/api-keys/" id "/all-tenants")
                        :path-info (str "/console-api/api-keys/" id "/all-tenants")
                        :request-method :put
                        :headers (cond-> {"content-type" "application/json"}
                                   api-key (assoc "authorization" (str "Bearer " api-key)))
                        :cookies (if user {"auth-token" {:value "jwt"}} {})
                        :body (io/input-stream (.getBytes (json/generate-string body) "UTF-8"))})]
        (assoc r :text (str (:body r)))))))

;; =============================================================================
;; The console route
;; =============================================================================

(deftest the-console-route-sets-and-clears-the-marker-for-any-admin-only
  (with-store
    (fn [conn {:keys [id plaintext]}]
      (testing "no JWT: wrap-admin-auth answers 401, the key is untouched"
        (let [r (put-marker id {:all-tenants true})]
          (is (= 401 (:status r)))
          (is (str/includes? (:text r) "Unauthorized") "the answering layer is wrap-admin-auth's 401")
          (is (false? (marked? conn plaintext)))))
      (testing "an API KEY is not a console credential: 401, even when it is the key itself"
        (let [r (put-marker id {:all-tenants true} :api-key plaintext)]
          (is (= 401 (:status r)))
          (is (str/includes? (:text r) "Unauthorized"))
          (is (false? (marked? conn plaintext)))))
      (testing "a permissioned NON-admin: 403 from wrap-admin-auth, nothing written, nothing audited"
        (let [r (put-marker id {:all-tenants true} :user reader)]
          (is (= 403 (:status r)))
          (is (str/includes? (:text r) "Admin access required") "the answering layer is wrap-admin-auth's 403")
          (is (false? (marked? conn plaintext)))
          (is (empty? (marker-audits conn id)))))
      (testing "an admin who did NOT create the key sets it (any admin)"
        (let [r (put-marker id {:all-tenants true} :user admin)]
          (is (= 200 (:status r)) (:text r))
          (is (= {:api-key-id id :all-tenants true} (json/parse-string (:text r) true)))
          (is (true? (marked? conn plaintext)))))
      (testing "and clears it"
        (let [r (put-marker id {:all-tenants false} :user admin)]
          (is (= 200 (:status r)) (:text r))
          (is (false? (marked? conn plaintext))))))))

(deftest the-console-route-refuses-a-bad-body-an-unknown-key-and-a-policyless-key
  (with-store
    (fn [conn {:keys [id plaintext]}]
      (testing "a non-boolean is a 400 (the route's body coercion), and nothing is written"
        (let [r (put-marker id {:all-tenants "yes"} :user admin)]
          (is (= 400 (:status r)) (:text r))
          (is (= "Request validation failed" (:error (json/parse-string (:text r) true)))
              "the answering layer is the route's declared body, not the setter's own check")
          (is (false? (marked? conn plaintext)))))
      (testing "an absent field is a 400 too: absence never marks"
        (let [r (put-marker id {} :user admin)]
          (is (= 400 (:status r)))
          (is (= "Request validation failed" (:error (json/parse-string (:text r) true)))))
        (is (false? (marked? conn plaintext))))
      (testing "an unknown key: 404"
        (is (= 404 (:status (put-marker "no-such-key" {:all-tenants true} :user admin)))))
      (testing "a key with NO policy has nothing to mark: 409"
        (d/transact conn {:tx-data [{:api-key/id "legacy-no-policy" :api-key/name "legacy"
                                     :api-key/key-digest "digest-x" :api-key/revoked false}]})
        (is (= 409 (:status (put-marker "legacy-no-policy" {:all-tenants true} :user admin))))))))

;; =============================================================================
;; The audit trail
;; =============================================================================

(deftest every-set-and-clear-is-audited-with-who-and-the-old-and-new-value
  (with-store
    (fn [conn {:keys [id]}]
      (api-keys/set-all-tenants! conn id true {:user-id admin :user-email "admin@example.test"})
      (api-keys/set-all-tenants! conn id false {:user-id admin :user-email "admin@example.test"})
      (let [[unmark mark] (marker-audits conn id)]
        (is (= :mark-all-tenants (:audit/action mark)))
        (is (= admin (:audit/user-id mark)))
        (is (= (pr-str {:all-tenants? false}) (:audit/previous-value mark)) "the old value: absent reads as false")
        (is (= (pr-str {:all-tenants? true}) (:audit/new-value mark)))
        (is (= :unmark-all-tenants (:audit/action unmark)))
        (is (= (pr-str {:all-tenants? true}) (:audit/previous-value unmark)))
        (is (= (pr-str {:all-tenants? false}) (:audit/new-value unmark)))))))

;; =============================================================================
;; The key UI's door: the SAME predicate as the route
;; =============================================================================

(def ^:private ui-door 'digdir.config.ui.api-keys/set-all-tenants-as-admin!)

(deftest the-ui-door-refuses-a-non-admin-on-the-server
  (with-store
    (fn [conn {:keys [id plaintext]}]
      (require 'digdir.config.ui.api-keys)
      (is (= {:status 403 :reason :not-admin}
             (refusal #(call ui-door conn reader "reader@example.test" id true)))
          "a permissioned non-admin driving the call without the button")
      (is (= {:status 403 :reason :not-admin}
             (refusal #(call ui-door conn nil nil id true)))
          "a session that lost its user")
      (is (false? (marked? conn plaintext)))
      (is (true? (call ui-door conn admin "admin@example.test" id true)) "CONTROL: an admin")
      (is (true? (marked? conn plaintext)))
      (is (= admin (:audit/user-id (last (marker-audits conn id)))) "the UI's write is audited as its admin"))))

(deftest both-doors-decide-admin-by-the-one-predicate
  (with-store
    (fn [conn {:keys [id plaintext]}]
      (require 'digdir.config.ui.api-keys)
      (let [asked (atom [])]
        (testing "the predicate says NO for everyone: both doors refuse"
          (with-redefs [perms/is-admin? (fn [_ uid] (swap! asked conj uid) false)]
            (is (= 403 (:status (put-marker id {:all-tenants true} :user admin))))
            (is (= {:status 403 :reason :not-admin}
                   (refusal #(call ui-door conn admin "admin@example.test" id true)))))
          (is (= [admin admin] @asked) "each door asked perms/is-admin? exactly once")
          (is (false? (marked? conn plaintext))))
        (testing "the predicate says YES even for a reader: both doors allow"
          (with-redefs [perms/is-admin? (fn [_ _] true)]
            (is (= 200 (:status (put-marker id {:all-tenants true} :user reader))))
            (is (true? (marked? conn plaintext)) "the route's write")
            (is (false? (call ui-door conn reader "reader@example.test" id false)) "the UI's write clears it")
            (is (false? (marked? conn plaintext)))))))))

;; =============================================================================
;; Rotate keeps the marker
;; =============================================================================

(deftest rotate-keeps-the-marker-and-an-unmarked-key-stays-unmarked
  (with-store
    (fn [conn {:keys [id plaintext]}]
      (testing "CONTROL: an unmarked key rotates unmarked"
        (let [{fresh :api-key} (api-keys/rotate-api-key! conn id {:user-id creator})]
          (is (false? (marked? conn fresh)))
          (is (nil? (api-keys/validate-api-key conn plaintext)) "the old key is revoked")))
      (let [k (make-key! conn "marked" {:dataset-scopes [{:tenant "kt" :dataset-config-key "ds-kt"}]})
            _ (api-keys/set-all-tenants! conn (:id k) true {:user-id admin})
            {fresh :api-key} (api-keys/rotate-api-key! conn (:id k) {:user-id creator})]
        (is (true? (marked? conn fresh)) "the rotated key lost its all-tenant marker")
        (is (nil? (api-keys/validate-api-key conn (:plaintext k))) "the old key is revoked")))))

;; =============================================================================
;; The UI's tenant label: from the one granted set and the marker
;; =============================================================================

(deftest the-tenant-label-derives-from-the-granted-set-and-the-marker
  (with-store
    (fn [conn {:keys [id]}]
      (let [tenant-only (:id (make-key! conn "tenant only" {}))
            _ (d/transact conn {:tx-data [{:api-key/id tenant-only :api-key/tenants ["kt"]}]})
            none (:id (make-key! conn "no grant" {}))
            reach (fn [kid] (call 'digdir.config.api-keys/tenant-reach
                                  (some #(when (= kid (:api-key/id %)) %) (api-keys/list-all-api-keys @conn))))]
        (is (= :granted (reach id)) "a dataset-scoped key")
        (is (= :granted (reach tenant-only)) "a tenant grant with NO dataset scope: from the granted union, not the dataset scopes")
        (is (= :no-tenant (reach none)) "no grant and no marker reaches NO tenant, never 'unrestricted'")
        (api-keys/set-all-tenants! conn none true {:user-id admin})
        (is (= :all-tenants (reach none)))))))
