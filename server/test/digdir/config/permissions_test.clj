(ns digdir.config.permissions-test
  "Tests for ABAC permission evaluation."
  (:require [clojure.test :refer [deftest testing is]]
            [datahike.api :as d]
            [digdir.config.schema :as schema]
            [digdir.config.db :as config-db]
            [digdir.config.permissions :as perms]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn create-test-db
  "Create an in-memory test database with schema and seed data."
  []
  (let [cfg {:store {:backend :mem
                     :id (str "perm-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      ;; Add config schema (includes permission schema)
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn delete-test-db
  "Delete the test database."
  [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn seed-test-data!
  "Seed test data: users, permissions, and config definitions."
  [conn]
  (let [now (System/currentTimeMillis)]
    ;; Create users
    (d/transact conn {:tx-data [{:user/id "user-admin"
                                 :user/email "admin@digdir.no"}
                                {:user/id "user-editor"
                                 :user/email "editor@digdir.no"}
                                {:user/id "user-readonly"
                                 :user/email "readonly@digdir.no"}
                                {:user/id "user-none"
                                 :user/email "none@digdir.no"}]})

    ;; Create permissions
    (d/transact conn {:tx-data [{:permission/id "admin-full"
                                 :permission/name "Full Admin"
                                 :permission/description "Full access"
                                 :permission/attributes (pr-str {:service :*
                                                                 :sensitivity :*
                                                                 :function :*})
                                 :permission/tenants (pr-str :*)
                                 :permission/environments (pr-str :*)
                                 :permission/actions (pr-str #{:read :write})
                                 :permission/created-at now}

                                {:permission/id "prompt-editor"
                                 :permission/name "Prompt Editor"
                                 :permission/description "Can edit prompts"
                                 :permission/attributes (pr-str {:service :*
                                                                 :sensitivity #{:public :internal}
                                                                 :function :prompts})
                                 :permission/tenants (pr-str :*)
                                 :permission/environments (pr-str :*)
                                 :permission/actions (pr-str #{:read :write})
                                 :permission/created-at now}

                                {:permission/id "readonly-public"
                                 :permission/name "Read Only Public"
                                 :permission/description "Read public settings"
                                 :permission/attributes (pr-str {:service :*
                                                                 :sensitivity #{:public}
                                                                 :function :*})
                                 :permission/tenants (pr-str :*)
                                 :permission/environments (pr-str :*)
                                 :permission/actions (pr-str #{:read})
                                 :permission/created-at now}

                                {:permission/id "ka-only"
                                 :permission/name "KA Tenant Only"
                                 :permission/description "Access only KA tenant"
                                 :permission/attributes (pr-str {:service :*
                                                                 :sensitivity :*
                                                                 :function :*})
                                 :permission/tenants (pr-str #{"ka"})
                                 :permission/environments (pr-str :*)
                                 :permission/actions (pr-str #{:read :write})
                                 :permission/created-at now}]})

    ;; Assign permissions to users
    (perms/grant-permission! conn "user-admin" "admin-full")
    (perms/grant-permission! conn "user-editor" "prompt-editor")
    (perms/grant-permission! conn "user-readonly" "readonly-public")

    ;; Create config definitions
    (config-db/upsert-definition! conn
                                  {:path "services.azure-openai.api-key"
                                   :root :platform
                                   :value-type :string
                                   :encrypted? true
                                   :category :services
                                   :service :llm
                                   :sensitivity :secret
                                   :function :credentials})

    (config-db/upsert-definition! conn
                                  {:path "chat.system-prompt"
                                   :root :runtime
                                   :value-type :string
                                   :encrypted? false
                                   :category :chat
                                   :service :llm
                                   :sensitivity :internal
                                   :function :prompts})

    (config-db/upsert-definition! conn
                                  {:path "feature.dark-mode"
                                   :root :platform
                                   :value-type :boolean
                                   :encrypted? false
                                   :category :features
                                   :service :other
                                   :sensitivity :public
                                   :function :features})))

;; =============================================================================
;; Attribute Parsing Tests
;; =============================================================================

(deftest test-parse-attribute-spec
  (testing "Wildcard parsing"
    (is (= {:type :wildcard} (perms/parse-attribute-spec :*))))

  (testing "Exact match parsing"
    (is (= {:type :exact :value :llm} (perms/parse-attribute-spec :llm)))
    (is (= {:type :exact :value :secret} (perms/parse-attribute-spec :secret))))

  (testing "Set parsing"
    (is (= {:type :set :values #{:llm :search}}
           (perms/parse-attribute-spec #{:llm :search}))))

  (testing "Negation parsing"
    (is (= {:type :negation :value :credentials}
           (perms/parse-attribute-spec :!credentials)))
    (is (= {:type :negation :value :secret}
           (perms/parse-attribute-spec :!secret)))))

(deftest test-matches-attribute
  (testing "Wildcard matches everything"
    (is (perms/matches-attribute? :llm :*))
    (is (perms/matches-attribute? :secret :*))
    (is (perms/matches-attribute? :anything :*)))

  (testing "Exact match"
    (is (perms/matches-attribute? :llm :llm))
    (is (not (perms/matches-attribute? :search :llm))))

  (testing "Set match"
    (is (perms/matches-attribute? :llm #{:llm :search}))
    (is (perms/matches-attribute? :search #{:llm :search}))
    (is (not (perms/matches-attribute? :auth #{:llm :search}))))

  (testing "Negation match"
    (is (perms/matches-attribute? :settings :!credentials))
    (is (perms/matches-attribute? :prompts :!credentials))
    (is (not (perms/matches-attribute? :credentials :!credentials)))))

;; =============================================================================
;; Permission Matching Tests
;; =============================================================================

(deftest test-permission-matches
  (let [conn (create-test-db)]
    (try
      (seed-test-data! conn)
      (let [db @conn
            admin-perm (perms/get-permission db "admin-full")
            prompt-perm (perms/get-permission db "prompt-editor")
            readonly-perm (perms/get-permission db "readonly-public")
            ka-perm (perms/get-permission db "ka-only")

            secret-def (config-db/get-definition db "services.azure-openai.api-key")
            prompt-def (config-db/get-definition db "chat.system-prompt")
            public-def (config-db/get-definition db "feature.dark-mode")]

        (testing "Admin can access everything"
          (is (perms/permission-matches? admin-perm secret-def :read nil nil))
          (is (perms/permission-matches? admin-perm secret-def :write nil nil))
          (is (perms/permission-matches? admin-perm prompt-def :read nil nil))
          (is (perms/permission-matches? admin-perm public-def :write nil nil)))

        (testing "Prompt editor can access prompts"
          (is (perms/permission-matches? prompt-perm prompt-def :read nil nil))
          (is (perms/permission-matches? prompt-perm prompt-def :write nil nil)))

        (testing "Prompt editor cannot access secrets"
          (is (not (perms/permission-matches? prompt-perm secret-def :read nil nil)))
          (is (not (perms/permission-matches? prompt-perm secret-def :write nil nil))))

        (testing "Readonly can only read public"
          (is (perms/permission-matches? readonly-perm public-def :read nil nil))
          (is (not (perms/permission-matches? readonly-perm public-def :write nil nil)))
          (is (not (perms/permission-matches? readonly-perm secret-def :read nil nil))))

        (testing "Tenant-restricted permission"
          (is (perms/permission-matches? ka-perm public-def :read "ka" nil))
          (is (not (perms/permission-matches? ka-perm public-def :read "altinn" nil)))))
      (finally
        (delete-test-db conn)))))

;; =============================================================================
;; Access Control Tests
;; =============================================================================

(deftest test-can-access
  (let [conn (create-test-db)]
    (try
      (seed-test-data! conn)
      (let [db @conn]

        (testing "Admin can access secrets"
          (is (perms/can-access? db "user-admin" "services.azure-openai.api-key" :read))
          (is (perms/can-access? db "user-admin" "services.azure-openai.api-key" :write)))

        (testing "Editor can access prompts"
          (is (perms/can-access? db "user-editor" "chat.system-prompt" :read))
          (is (perms/can-access? db "user-editor" "chat.system-prompt" :write)))

        (testing "Editor cannot access secrets"
          (is (not (perms/can-access? db "user-editor" "services.azure-openai.api-key" :read))))

        (testing "Readonly can read public"
          (is (perms/can-access? db "user-readonly" "feature.dark-mode" :read)))

        (testing "Readonly cannot write"
          (is (not (perms/can-access? db "user-readonly" "feature.dark-mode" :write))))

        (testing "User with no permissions cannot access anything"
          (is (not (perms/can-access? db "user-none" "feature.dark-mode" :read))))

        (testing "Non-existent config allows access by default"
          (is (perms/can-access? db "user-none" "non.existent.path" :read))))
      (finally
        (delete-test-db conn)))))

(deftest test-evaluate-access
  (let [conn (create-test-db)]
    (try
      (seed-test-data! conn)
      (let [db @conn]

        (testing "Successful access returns matched permission"
          (let [result (perms/evaluate-access db "user-admin" "services.azure-openai.api-key" :read)]
            (is (:allowed? result))
            (is (= "admin-full" (:matched-permission result)))))

        (testing "Failed access returns reason"
          (let [result (perms/evaluate-access db "user-editor" "services.azure-openai.api-key" :read)]
            (is (not (:allowed? result)))
            (is (nil? (:matched-permission result)))
            (is (string? (:reason result)))))

        (testing "User with no permissions gets clear reason"
          (let [result (perms/evaluate-access db "user-none" "feature.dark-mode" :read)]
            (is (not (:allowed? result)))
            (is (= "User has no permissions assigned" (:reason result))))))
      (finally
        (delete-test-db conn)))))

;; =============================================================================
;; Permission Management Tests
;; =============================================================================

(deftest test-permission-crud
  (let [conn (create-test-db)]
    (try
      (let [_now (System/currentTimeMillis)]
        ;; Create user
        (d/transact conn {:tx-data [{:user/id "test-user"
                                     :user/email "test@digdir.no"}]})

        (testing "Create permission"
          (perms/create-permission! conn
                                    {:id "test-perm"
                                     :name "Test Permission"
                                     :description "A test permission"
                                     :attributes {:service :llm :sensitivity :public :function :settings}
                                     :tenants :*
                                     :environments :*
                                     :actions #{:read}})
          (let [perm (perms/get-permission @conn "test-perm")]
            (is (some? perm))
            (is (= "Test Permission" (:permission/name perm)))))

        (testing "Grant permission to user"
          (perms/grant-permission! conn "test-user" "test-perm")
          (let [user-perms (perms/get-user-permissions @conn "test-user")]
            (is (= 1 (count user-perms)))
            (is (= "test-perm" (:permission/id (first user-perms))))))

        (testing "Revoke permission from user"
          (perms/revoke-permission! conn "test-user" "test-perm")
          (let [user-perms (perms/get-user-permissions @conn "test-user")]
            (is (empty? user-perms))))

        (testing "Get all permissions"
          (let [all-perms (perms/get-all-permissions @conn)]
            (is (= 1 (count all-perms))))))
      (finally
        (delete-test-db conn)))))

(deftest test-is-admin
  (let [conn (create-test-db)]
    (try
      (seed-test-data! conn)
      (let [db @conn]
        (testing "Admin user is identified"
          (is (perms/is-admin? db "user-admin")))

        (testing "Non-admin is not identified as admin"
          (is (not (perms/is-admin? db "user-editor")))
          (is (not (perms/is-admin? db "user-none")))))
      (finally
        (delete-test-db conn)))))

(deftest test-filter-accessible-configs
  (let [conn (create-test-db)]
    (try
      (seed-test-data! conn)
      (let [db @conn
            all-defs (config-db/get-all-definitions db)]

        (testing "Admin can access all configs"
          (let [accessible (perms/filter-accessible-configs db "user-admin" all-defs :read)]
            (is (= 3 (count accessible)))))

        (testing "Readonly can only access public"
          (let [accessible (perms/filter-accessible-configs db "user-readonly" all-defs :read)]
            (is (= 1 (count accessible)))
            (is (= "feature.dark-mode" (:config-def/path (first accessible))))))

        (testing "No permissions = no access"
          (let [accessible (perms/filter-accessible-configs db "user-none" all-defs :read)]
            (is (empty? accessible)))))
      (finally
        (delete-test-db conn)))))

(deftest test-validate-permission-attributes
  (testing "Valid attributes pass"
    (let [result (perms/validate-permission-attributes
                  {:service :llm
                   :sensitivity :secret
                   :function :credentials})]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "Wildcard is valid"
    (let [result (perms/validate-permission-attributes
                  {:service :*
                   :sensitivity :*
                   :function :*})]
      (is (:valid? result))))

  (testing "Sets are valid"
    (let [result (perms/validate-permission-attributes
                  {:service #{:llm :search}
                   :sensitivity #{:public :internal}
                   :function :*})]
      (is (:valid? result))))

  (testing "Negation is valid"
    (let [result (perms/validate-permission-attributes
                  {:service :*
                   :sensitivity :!secret
                   :function :!credentials})]
      (is (:valid? result))))

  (testing "Invalid service is caught"
    (let [result (perms/validate-permission-attributes
                  {:service :invalid-service})]
      (is (not (:valid? result)))
      (is (some #(re-find #"Invalid service" %) (:errors result))))))

;; =============================================================================
;; User Permission Helpers
;; =============================================================================

(deftest test-can-manage-config
  (let [conn (create-test-db)]
    (try
      (seed-test-data! conn)
      (let [db @conn]
        (testing "User with permissions can manage config"
          (is (perms/can-manage-config? db "user-admin"))
          (is (perms/can-manage-config? db "user-editor")))

        (testing "User without permissions cannot manage config"
          (is (not (perms/can-manage-config? db "user-none")))))
      (finally
        (delete-test-db conn)))))

(deftest test-get-users-with-permission
  (let [conn (create-test-db)]
    (try
      (seed-test-data! conn)
      (let [db @conn
            admin-users (perms/get-users-with-permission db "admin-full")]
        (is (= 1 (count admin-users)))
        (is (= "user-admin" (:user/id (first admin-users)))))
      (finally
        (delete-test-db conn)))))

;; =============================================================================
;; Login Access Tests (New permission-based login)
;; =============================================================================

(deftest test-user-has-any-permission
  (let [conn (create-test-db)]
    (try
      (seed-test-data! conn)
      (let [db @conn]
        (testing "User with permissions returns true"
          (is (perms/user-has-any-permission? db "user-admin"))
          (is (perms/user-has-any-permission? db "user-editor"))
          (is (perms/user-has-any-permission? db "user-readonly")))

        (testing "User without permissions returns falsy"
          (is (not (perms/user-has-any-permission? db "user-none"))))

        (testing "Non-existent user returns falsy"
          (is (not (perms/user-has-any-permission? db "non-existent-user")))))
      (finally
        (delete-test-db conn)))))

(deftest test-can-login
  (let [conn (create-test-db)]
    (try
      (seed-test-data! conn)
      (let [db @conn]
        (testing "User with permissions can login"
          (let [result (perms/can-login? db "admin@digdir.no")]
            (is (:allowed? result))
            (is (some? (:user result)))
            (is (= "user-admin" (get-in result [:user :user/id])))))

        (testing "User without permissions cannot login"
          (let [result (perms/can-login? db "none@digdir.no")]
            (is (not (:allowed? result)))
            (is (some? (:user result)))
            (is (re-find #"pending" (:reason result)))))

        (testing "Non-existent user cannot login"
          (let [result (perms/can-login? db "nonexistent@example.com")]
            (is (not (:allowed? result)))
            (is (nil? (:user result)))
            (is (re-find #"not found" (:reason result))))))
      (finally
        (delete-test-db conn)))))

;; =============================================================================
;; Run Tests
;; =============================================================================

(comment
  ;; Run all tests
  (clojure.test/run-tests 'digdir.config.permissions-test)

  ;; Run specific test
  (test-can-access))
