(ns digdir.config.ui.console-admin-gate-test
  "the console's server functions are
   admin-gated ON THE SERVER, by the ONE guard, whatever the client draws.

   - G1/G2: every gated helper refuses a non-admin actor and a nil actor, and a
     refusal leaves the store's datoms EQUAL; an admin gets past the guard (the
     control), asserted by the refusal it does NOT get.
   - G4: each API-key operation gives the same verdict through the console HTTP
     handler and through the Electric panel's server fn, for four actors.
   - G5: the panel's reads return nothing to a non-admin.
   - G6: `mutate-config-tree!` refuses a non-admin; `skills-api/initialize!`
     writes nothing.
   - `created-by` holds the creator's user ID, as ATTRIBUTION only.
   - ANY admin may do ANY key operation on ANY key, through
     both doors; the creator decides nothing.

   The server fns are called directly (the clj body of each `e/server` call);
   the websocket is not driven."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [digdir.api.routes.handlers :as handlers]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.db :as config-db :refer [*conn*]]
            [digdir.config.permissions :as perms]
            [digdir.config.schema :as schema]
            [digdir.config.ui.api-keys :as panel]
            [digdir.config.ui.common :as common]
            [digdir.data.db :as data-db]
            [digdir.pipeline.ui.pipelines :as pipelines]
            [digdir.skills.api :as skills-api]))

(defn- v
  "The var `sym`'s fn, or a fn returning `::missing-at-base`: the console admin-gate fix adds these
   vars, and this namespace must LOAD at its base, where each arm then FAILS by
   assertion (a value no arm expects) rather than erroring."
  [sym]
  (or (try (some-> (requiring-resolve sym) deref) (catch Exception _ nil))
      (fn [& _] ::missing-at-base)))

(def ^:private admin "admin-1")
(def ^:private admin-email "admin1@example.test")
(def ^:private other-admin "admin-2")
(def ^:private reader "reader-3")

(def ^:dynamic ^:private *c* nil)

(defn- with-store [f]
  (let [cfg {:store {:backend :mem :id (str "console-admin-gate-" (random-uuid))} :schema-flexibility :read}
        _ (d/create-database cfg)
        conn (d/connect cfg)]
    (d/transact conn {:tx-data data-db/dh-schema})
    (d/transact conn {:tx-data schema/config-migration-schema})
    (with-redefs [config-db/get-conn (constantly conn)
                  data-db/get-conn (constantly conn)]
      (binding [*conn* conn *c* conn]
        (config-db/ensure-schema! conn)
        (d/transact conn {:tx-data [{:permission/id "admin-full" :permission/name "Full admin"}
                                    {:permission/id "reader" :permission/name "Read only"}
                                    {:user/id admin :user/email admin-email}
                                    {:user/id other-admin :user/email "admin2@example.test"}
                                    {:user/id reader :user/email "reader@example.test"}]})
        (perms/grant-permission! conn admin "admin-full")
        (perms/grant-permission! conn other-admin "admin-full")
        (perms/grant-permission! conn reader "reader")
        (try (f) (finally (d/release conn)))))))

(use-fixtures :each with-store)

(defn- datoms [] (count (d/datoms @*c* :eavt)))

(defn- outcome
  "What `f` did, as data: the guard's refusal, another refusal, or a value."
  [f]
  (try
    (let [v (f)]
      (if (and (map? v) (= "Permission denied - admin required" (:error v)))
        :refused-admin
        [:value v]))
    (catch clojure.lang.ExceptionInfo e
      (cond
        ;; the one guard's refusal, by its reason or (before the console admin-gate fix enriched it) its message
        (or (= :not-admin (:reason (ex-data e)))
            (= "Permission denied - admin required" (ex-message e))) :refused-admin
        (= :not-creator (:reason (ex-data e))) :refused-creator
        :else [:other-error (ex-message e)]))
    (catch Exception e [:other-error (ex-message e)])))

(defn- key! [created-by]
  (let [plaintext (:api-key (api-keys/create-api-key! *c* "k" created-by
                                                      {:scopes #{:query}
                                                       :dataset-scopes [{:tenant "kt" :dataset-config-key "ds"}]}))]
    (:api-key-id (api-keys/validate-api-key *c* plaintext))))

;; =============================================================================
;; G1/G2: every gated helper, three actors
;; =============================================================================

(def ^:private helpers
  "name -> (fn [actor] call). The key panel's, Group A's (with their previews),
   the pipelines panel's, and mutate-config-tree!."
  {"panel-create-key!" #((v 'digdir.config.ui.api-keys/panel-create-key!) *c* % "k" {:scopes #{:query} :dataset-scopes [{:tenant "kt" :dataset-config-key "ds"}]})
   "panel-revoke-key!" #((v 'digdir.config.ui.api-keys/panel-revoke-key!) *c* % "e@x" (key! admin))
   "panel-replace-allowed-config-keys!" #((v 'digdir.config.ui.api-keys/panel-replace-allowed-config-keys!) *c* % "e@x" (key! admin) [])
   "set-all-tenants-as-admin!" #(panel/set-all-tenants-as-admin! *c* % "e@x" (key! admin) true)
   "export-preview" #(common/export-preview "kt" false %)
   "do-export!" #(common/do-export! "kt" false "SYNTHSECRET-pw" %)
   "import-preview" #(common/import-preview "{}" :skip %)
   "do-import!" #(common/do-import! "{}" "SYNTHSECRET-pw" :skip %)
   "preview-clone-tenant" #(common/preview-clone-tenant "kt" "kt2" false %)
   "do-clone-tenant!" #(common/do-clone-tenant! "kt" "kt2" false %)
   "preview-tenant-retirement" #(common/preview-tenant-retirement "kt" "default" %)
   "do-retire-source-tenants!" #(common/do-retire-source-tenants! "kt" "default" :keep %)
   "do-bootstrap-deployment-target-topology!" #(common/do-bootstrap-deployment-target-topology! "default" %)
   "create-dataset!" #(#'pipelines/create-dataset! % "n" "d")
   "update-dataset!" #(#'pipelines/update-dataset! % {:dataset-id "no-such" :name "n"})
   "create-pipeline!" #(#'pipelines/create-pipeline! % {:tenant "kt" :tenant-config-key "default" :dataset-id "no-such" :pipeline-id "p" :name "p" :source-type "website"})
   "update-pipeline!" #(#'pipelines/update-pipeline! % {:tenant "kt" :tenant-config-key "default" :pipeline-id "p" :name "p" :source-type "website"})
   "delete-pipeline!" #(#'pipelines/delete-pipeline! % "kt" "default" "p")
   "execute-pipeline!" #(#'pipelines/execute-pipeline! % "kt" "default" "p")
   "stop-pipeline-execution!" #(#'pipelines/stop-pipeline-execution! % "no-such-execution")
   "mutate-config-tree!" #(common/mutate-config-tree! {:op :set-node-value :tenant "kt" :root :runtime
                                                       :node-id "runtime/kt/default" :path "skills.x" :value "1"
                                                       :user-id %})})

(deftest g1-g2-every-gated-helper-refuses-a-non-admin-and-a-nil-actor-and-writes-nothing
  (is (= 21 (count helpers)) "PREMISE: the helpers this test was written against")
  (doseq [[nm call] (sort-by key helpers)
          [label actor] [["a permissioned non-admin" reader] ["a nil actor" nil] ["an unknown actor" "no-such-user"]]]
    (testing (str nm ", " label)
      (let [o (outcome #(call actor))]
        (is (= :refused-admin o) (str "not refused by the one guard: " (pr-str o))))))
  (testing "a refusal leaves the store EQUAL, and the SAME call by an admin changes it (the probe can see a write)"
    (let [kid (key! admin)]
      (doseq [[nm call] {"panel-create-key!" #((v 'digdir.config.ui.api-keys/panel-create-key!) *c* % "k" {:scopes #{:query}})
                         "panel-revoke-key!" #((v 'digdir.config.ui.api-keys/panel-revoke-key!) *c* % "e@x" kid)
                         "set-all-tenants-as-admin!" #(panel/set-all-tenants-as-admin! *c* % "e@x" kid true)
                         "create-dataset!" #(#'pipelines/create-dataset! % "n" "d")
                         "mutate-config-tree! :create-node" #(common/mutate-config-tree! {:op :create-node :tenant "kt" :root :runtime
                                                                                         :node-id "runtime/kt/probe" :label "probe"
                                                                                         :tenant-config-key "probe" :user-id %})}]
        (testing nm
          (let [before (datoms)]
            (outcome #(call reader))
            (is (= before (datoms)) "a refused call wrote datoms")
            (outcome #(call admin))
            (is (< before (datoms)) "CONTROL: the admin's call did not write, so this probe cannot see a write")))))))

(deftest g1-control-an-admin-gets-past-the-guard-at-every-helper
  (doseq [[nm call] (sort-by key helpers)]
    (testing nm
      (let [o (outcome #(call admin))]
        (is (not= :refused-admin o) (str "an ADMIN was refused by the guard: " (pr-str o)))))))

;; =============================================================================
;; G5: the panel's reads
;; =============================================================================

(deftest g5-the-key-panels-reads-return-nothing-to-a-non-admin
  (key! admin)
  (let [read-keys #((v 'digdir.config.ui.api-keys/panel-read) @*c* % (fn [] (api-keys/list-all-api-keys @*c*)))]
    (is (let [r (read-keys admin)] (and (coll? r) (= 1 (count r)))) "CONTROL: an admin sees the key")
    (is (nil? (read-keys reader)) "a non-admin saw key metadata")
    (is (nil? (read-keys nil)) "a nil actor saw key metadata")))

;; =============================================================================
;; G6: the siblings
;; =============================================================================

(deftest g6-skills-initialize-writes-nothing-to-the-store
  (let [before (datoms)]
    (skills-api/initialize!)
    (is (= before (datoms)) "skills-api/initialize! wrote to the store")))

(deftest g6-the-one-guard-is-the-admin-predicate
  (is (true? ((v 'digdir.config.ui.common/config-ui-admin?) @*c* admin)))
  (is (false? ((v 'digdir.config.ui.common/config-ui-admin?) @*c* reader)))
  (is (false? ((v 'digdir.config.ui.common/config-ui-admin?) @*c* nil)) "a nil actor is never an admin (the Datalog nil-:in lesson)")
  (is (= {:status 403 :reason :not-admin}
         (try (common/ensure-config-ui-admin! @*c* reader) nil
              (catch clojure.lang.ExceptionInfo e (ex-data e))))
      "the refusal an HTTP door answers as a 403"))

;; =============================================================================
;; G4: the HTTP door and the Electric door give the same verdict
;; =============================================================================

(defn- http-verdict [response]
  (case (:status response) (200 201) :allowed 403 :refused [:unexpected (:status response) (:body response)]))

(defn- panel-verdict [f]
  (let [o (outcome f)]
    (cond (#{:refused-admin :refused-creator} o) :refused
          (= :value (first o)) :allowed
          :else o)))

(defn- req [actor key-id body]
  {:user/id actor :user/email "e@x" :path-params {:key-id key-id} :body-params body})

(def ^:private ops
  {:create {:http #(handlers/create-api-key-handler (req % nil {:name "k" :dataset-scopes [{:tenant "kt" :dataset-config-key "ds"}] :allowed-config-keys []}))
            :panel #((v 'digdir.config.ui.api-keys/panel-create-key!) *c* % "k" {:scopes #{:query} :dataset-scopes [{:tenant "kt" :dataset-config-key "ds"}]})
            :expected {reader :refused other-admin :allowed admin :allowed nil :refused}}
   :revoke {:http #(handlers/revoke-api-key-handler (req %1 %2 nil))
            :panel #((v 'digdir.config.ui.api-keys/panel-revoke-key!) *c* %1 "e@x" %2)
            :expected {reader :refused other-admin :allowed admin :allowed nil :refused}}
   :replace-allowed-config-keys
   {:http #(handlers/update-api-key-allowed-config-keys-handler (req %1 %2 {:allowed-config-keys []}))
    :panel #((v 'digdir.config.ui.api-keys/panel-replace-allowed-config-keys!) *c* %1 "e@x" %2 [])
    :expected {reader :refused other-admin :allowed admin :allowed nil :refused}}
   :set-all-tenants {:http #(handlers/set-api-key-all-tenants-handler (req %1 %2 {:all-tenants true}))
                     :panel #(panel/set-all-tenants-as-admin! *c* %1 "e@x" %2 true)
                     :expected {reader :refused other-admin :allowed admin :allowed nil :refused}}})

(deftest g4-each-key-operation-answers-alike-through-both-doors
  (with-redefs [config-db/get-dataset-by-ref (fn [& _] {:dataset/id "ds"})]
    (doseq [[op {:keys [http panel expected]}] ops
            actor [reader other-admin admin nil]]
      (testing (str op " as " (pr-str actor))
        (let [via-http (if (= op :create) (http-verdict (http actor)) (http-verdict (http actor (key! admin))))
              via-panel (if (= op :create) (panel-verdict #(panel actor)) (panel-verdict #(panel actor (key! admin))))]
          (is (= (expected actor) via-http) "the HTTP door")
          (is (= (expected actor) via-panel) "the Electric door"))))))

;; =============================================================================
;; created-by is the user ID; a legacy email matches narrowly
;; =============================================================================

(deftest both-doors-record-the-creator-by-user-id
  (with-redefs [config-db/get-dataset-by-ref (fn [& _] {:dataset/id "ds"})]
    (let [http-body (json/parse-string (:body (handlers/create-api-key-handler
                                               (req admin nil {:name "h" :dataset-scopes [{:tenant "kt" :dataset-config-key "ds"}] :allowed-config-keys []})))
                                       true)
          panel-key ((v 'digdir.config.ui.api-keys/panel-create-key!) *c* admin "p" {:scopes #{:query}})
          creators (set (map :api-key/created-by (api-keys/list-all-api-keys @*c*)))]
      (is (:api-key-id http-body) "CONTROL: the HTTP create succeeded")
      (is (:api-key panel-key) "CONTROL: the panel create succeeded")
      (is (= #{admin} creators) "a door recorded the creator by something other than the user ID"))))

;; =============================================================================
;; any admin may do any key operation on any key, both doors
;; =============================================================================

(defn- revoked? [kid] (:api-key/revoked (api-keys/get-api-key-info *c* kid)))
(defn- marked? [kid] (true? (get-in (api-keys/get-api-key-info *c* kid) [:api-key/policy :access-policy/all-tenants?])))

(deftest admin-b-manages-admin-as-key-through-both-doors
  (testing "the HTTP door"
    (let [kid (key! admin)]
      (is (= :allowed (http-verdict (handlers/update-api-key-allowed-config-keys-handler (req other-admin kid {:allowed-config-keys []})))))
      (is (= :allowed (http-verdict (handlers/set-api-key-all-tenants-handler (req other-admin kid {:all-tenants true})))))
      (is (marked? kid) "admin B's marker did not land")
      (is (= :allowed (http-verdict (handlers/revoke-api-key-handler (req other-admin kid nil)))))
      (is (revoked? kid) "admin B's revoke did not land"))
    (let [kid (key! admin)]
      (is (= :allowed (http-verdict (handlers/rotate-api-key-handler (req other-admin kid nil)))) "rotate")
      (is (revoked? kid) "the rotated key was not revoked")))
  (testing "the Electric door"
    (let [kid (key! admin)]
      (is (= :allowed (panel-verdict #((v 'digdir.config.ui.api-keys/panel-replace-allowed-config-keys!) *c* other-admin "e@x" kid []))))
      (is (= :allowed (panel-verdict #(panel/set-all-tenants-as-admin! *c* other-admin "e@x" kid true))))
      (is (marked? kid))
      (is (= :allowed (panel-verdict #((v 'digdir.config.ui.api-keys/panel-revoke-key!) *c* other-admin "e@x" kid))))
      (is (revoked? kid) "admin B's revoke did not land")))
  (testing "CONTROL: a non-admin still cannot, through either door"
    (let [kid (key! admin)]
      (is (= :refused (http-verdict (handlers/revoke-api-key-handler (req reader kid nil)))))
      (is (= :refused (panel-verdict #((v 'digdir.config.ui.api-keys/panel-revoke-key!) *c* reader "e@x" kid))))
      (is (not (revoked? kid))))))

(deftest both-doors-list-every-key-to-any-admin
  ;; An admin must FIND any key to act on it; the two doors answer alike.
  (let [ids #{(key! admin) (key! other-admin) (key! "someone-else")}
        via-http (fn [actor]
                   (let [r (handlers/list-api-keys-handler {:user/id actor :user/email "e@x"})]
                     (if (= 200 (:status r))
                       (set (map :api-key-id (:api-keys (json/parse-string (:body r) true))))
                       [:status (:status r)])))
        via-panel (fn [actor]
                    (let [r ((v 'digdir.config.ui.api-keys/panel-read) @*c* actor #(api-keys/list-all-api-keys @*c*))]
                      (if (coll? r) (set (map :api-key/id r)) r)))]
    (testing "admin B sees every key, including those it did not create, through both doors"
      (is (= ids (via-http other-admin)) "the HTTP door")
      (is (= ids (via-panel other-admin)) "the Electric door"))
    (testing "CONTROL: a non-admin sees none, through either door"
      (is (= [:status 403] (via-http reader)))
      (is (nil? (via-panel reader))))))
