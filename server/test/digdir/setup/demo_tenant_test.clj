(ns digdir.setup.demo-tenant-test
  "The demo tenant must resolve its OWN Typesense settings (#476).

   THE PROPERTY, stated once: a Typesense lookup for tenant `demo` returns the
   demo tenant's host, and never another tenant's.

   ⚠️ THIS DOCSTRING PREVIOUSLY CLAIMED MORE THAN HAD BEEN RUN. It said the
   guard held under BOTH the old resolver and #476's — written while
   #476 was still unmerged, so the second half was a claim about a resolver
   this test had never executed against. It did not hold: the fixture seeded a
   host and no key, and once #476 made `api-key-admin` a precondition, the
   tenants no longer resolved. A guard's docstring is exactly where a
   forward-looking claim reads as an established one, which is what makes that
   worse than the incomplete fixture it described.

   What is true now, #476 having merged: the tenant-less fallback is GONE, and
   a tenant that resolves nothing gets a named failure rather than somebody
   else's corpus. This guard asserts what `demo` resolves — which is the
   property under either resolver, and is now actually exercised against the
   one in the tree.

   Driven through the REAL resolver (`digdir.rag.typesense/make-ts-settings`)
   against a real in-memory config DB, rather than by inspecting the template's
   map — a template that is correct and never reaches the resolver would pass a
   shape check and fail in production."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.config.schema :as schema]
            [digdir.rag.retrieval :as retrieval]
            [digdir.rag.typesense :as ts-utils]
            [digdir.setup.demo-tenant :as demo-tenant]))

(def ^:private other-tenant
  "A tenant the demo must not borrow from. `digdir` by name on purpose: it is
   the first entry in the resolver's fallback list, so if the demo tenant ever
   resolves nothing, this is what it silently gets."
  "digdir")

(def ^:private other-tenant-host "other-tenant-typesense.invalid:9999")

(defn- create-test-db []
  (let [cfg {:store {:backend :mem :id (str "demo-tenant-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn- delete-test-db [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn- seed-typesense-definitions! [conn]
  (doseq [[path value-type] [["services.typesense.api-host" :string]
                             ["services.typesense.api-tls" :boolean]
                             ["services.typesense.api-key-admin" :string]
                             ["services.typesense.collection-prefix" :string]]]
    (config-db/upsert-definition! conn
                                  {:path path
                                   :root :platform
                                   :value-type value-type
                                   :encrypted? false
                                   :category :services
                                   :service :search
                                   :sensitivity :internal
                                   :function :settings})))

(defn- seed-tenant-platform-node! [conn tenant values]
  (config-db/create-config-node! conn
                                 {:root :platform
                                  :tenant tenant
                                  :node-id (str "platform-" tenant)
                                  :label tenant
                                  :tenant-config-key "default"})
  (doseq [[path value] values]
    (config-db/set-node-value! conn
                               {:root :platform
                                :tenant tenant
                                :node-id (str "platform-" tenant)
                                :path path
                                :value value
                                :master-key nil})))

(defmacro ^:private with-accessor-context [conn & body]
  `(do
     (config-db/set-conn! ~conn)
     (try
       (with-redefs [config-core/use-db-config? (constantly true)
                     config-core/get-master-key (constantly nil)]
         ~@body)
       (finally
         (config-db/set-conn! nil)))))

(deftest demo-tenant-resolves-its-own-typesense-settings
  (let [conn (create-test-db)]
    (try
      (seed-typesense-definitions! conn)
      ;; The other tenant is seeded FIRST and with a different host, so a demo
      ;; lookup that resolved nothing would have something wrong to fall into.
      ;; Without this the test could pass by there being nothing to borrow.
      ;; #476 made `api-key-admin` a required precondition, so a tenant with a
      ;; host but no key no longer resolves. The authored template deliberately
      ;; omits it — it is a credential, and arrives from the environment via
      ;; `seed-config-from-env!` — so the fixture supplies it here the way a
      ;; deployment does. EVERY ASSERTION BELOW IS UNCHANGED; the tenants are
      ;; simply now completely configured rather than half configured.
      (seed-tenant-platform-node! conn other-tenant
                                  {"services.typesense.api-host" other-tenant-host
                                   "services.typesense.api-tls" false
                                   "services.typesense.api-key-admin" "other-tenant-key"})
      (seed-tenant-platform-node! conn demo-tenant/demo-tenant
                                  (assoc demo-tenant/typesense-platform-values
                                         "services.typesense.api-key-admin" "demo-tenant-key"))

      (with-accessor-context conn
        (let [demo-settings (ts-utils/make-ts-settings {:tenant demo-tenant/demo-tenant})
              other-settings (ts-utils/make-ts-settings {:tenant other-tenant})]

          (testing "the control: the other tenant resolves, so there IS something to borrow"
            (is (some? other-settings))
            (is (= (str "http://" other-tenant-host) (:uri other-settings))
                "if this fails the test cannot detect borrowing at all"))

          (testing "the demo tenant resolves at all"
            (is (some? demo-settings)
                (str "demo resolved nothing, which is the state that makes the "
                     "resolver fall back to another tenant (#476)")))

          (testing "and resolves ITS OWN host, not the other tenant's"
            (is (= (str "http://" demo-tenant/typesense-host) (:uri demo-settings)))
            (is (not= (:uri other-settings) (:uri demo-settings))
                "the demo tenant is reading another tenant's Typesense host"))))
      (finally
        (delete-test-db conn)))))

(deftest demo-tenant-template-authors-no-secret
  ;; Secrets stay in the environment. `api-key-admin` is `:encrypted? true` and
  ;; must arrive via the env bridge at run time, never from an authored
  ;; template — the config master key is itself an open question (#288), so
  ;; "the storage layer would encrypt it" is not the reassurance it sounds like.
  (testing "no secret-bearing path is authored"
    (let [authored (set (keys demo-tenant/typesense-platform-values))
          secret-paths (->> (env-bridge/bindings-for-service :typesense)
                            (filter :secret?)
                            (map :path)
                            set)]
      (testing "the control: the binding table does mark a typesense path secret"
        (is (seq secret-paths)
            "if nothing is marked secret this assertion proves nothing"))
      (is (empty? (set/intersection authored secret-paths))
          (str "the template authors a secret-bearing path: "
               (pr-str (set/intersection authored secret-paths)))))))

(deftest demo-tenant-covers-every-non-secret-typesense-path
  ;; Derived from the env-bridge binding table rather than a list restated
  ;; here, so a NEW non-secret typesense path fails this instead of silently
  ;; leaving the demo tenant short of one.
  (testing "every non-secret config-DB typesense path is authored"
    (let [expected (->> (env-bridge/bindings-for-service :typesense)
                        (filter #(= :config-db (:destination %)))
                        (remove :secret?)
                        (map :path)
                        set)
          authored (set (keys demo-tenant/typesense-platform-values))]
      (testing "the contract is not vacuous"
        (is (<= 3 (count expected))
            (str "expected several non-secret typesense paths, saw " (count expected))))
      (is (empty? (set/difference expected authored))
          (str "the demo tenant template is missing: "
               (pr-str (sort (set/difference expected authored))))))))

(defn- path->settings-key
  "`services.typesense.api-host` -> `:api-host`, the shape
   `required-platform-keys` is written in."
  [path]
  (some-> (re-matches #"services\.typesense\.(.+)" path) second keyword))

(deftest demo-tenant-plus-env-covers-every-required-platform-key
  ;; THE COVERAGE INVARIANT, derived on all three sides:
  ;;
  ;;     authored ∪ env-supplied  ⊇  required-platform-keys
  ;;
  ;; NOT `authored = required`, which is the collapse an earlier note here
  ;; invited and which does not survive contact: `:api-tls` is authored and not
  ;; required, `collection-prefix` is authored and never read by the resolver,
  ;; and `:api-key-admin` is required and deliberately never authored because it
  ;; is a credential.
  ;;
  ;; Derived rather than restated so that adding a key to `required-platform-keys`
  ;; fails THIS test automatically, instead of leaving the demo tenant silently
  ;; short of a precondition — which is exactly how the fixture in this file came
  ;; to be half-configured before #476 exposed it.
  (let [required (set ts-utils/required-platform-keys)
        authored (->> (keys demo-tenant/typesense-platform-values)
                      (keep path->settings-key)
                      set)
        ;; The env bridge supplies precisely the secret-bearing paths the
        ;; template refuses to author. Derived from the same table the
        ;; no-secret test uses, so the two cannot disagree about which those are.
        env-supplied (->> (env-bridge/bindings-for-service :typesense)
                          (filter :secret?)
                          (map :path)
                          (keep path->settings-key)
                          set)
        covered (set/union authored env-supplied)]

    (testing "none of the three sides is vacuous"
      (is (seq required) "required-platform-keys is empty; this test would assert nothing")
      (is (seq authored) "the template authors nothing; the path mapping is probably wrong")
      (is (seq env-supplied) "no secret typesense path found; 'env supplies it' would prove nothing"))

    (testing "the split is real — the required set is NOT simply what is authored"
      (is (not= required authored)
          (str "if these were equal the coverage framing would be unnecessary, "
               "and the collapse this test exists to prevent would be correct")))

    (testing "every required key is covered by the template or by the environment"
      (is (empty? (set/difference required covered))
          (str "the demo tenant cannot resolve: no source supplies "
               (pr-str (sort (set/difference required covered))))))

    (testing "and the credential is covered by the ENVIRONMENT, not by the template"
      (is (contains? env-supplied :api-key-admin))
      (is (not (contains? authored :api-key-admin))
          "the template must never author the admin key"))))

;; ─────────────────────────────────────────────────────────────────────────────
;; #494 — the documented supply route that never ran
;; ─────────────────────────────────────────────────────────────────────────────

(deftest seed!-bridges-the-environment-for-this-tenant
  ;; ⚠️ THIS ASSERTS THE CALL HAPPENS, NOT THAT THE FUNCTION EXISTS.
  ;;
  ;; That distinction IS the defect. `env-bridge/seed-config-from-env!` existed,
  ;; declared the TYPESENSE_API_KEY_ADMIN mapping, and worked when invoked by
  ;; hand — and materialization still failed, because the only automatic caller
  ;; is `digdir.e2e.seed`, scoped to #{:azure-openai}. A test that checked the
  ;; mapping, or that the function writes what it is given, would have passed
  ;; throughout. Only a test of the CALL fails on the real bug.
  (let [conn (create-test-db)
        calls (atom [])]
    (try
      (seed-typesense-definitions! conn)
      (with-accessor-context conn
        (with-redefs [env-bridge/seed-config-from-env!
                      (fn [& args] (swap! calls conj (vec args)) {:stubbed true})]
          (let [result (demo-tenant/seed! demo-tenant/demo-tenant)]

            (testing "seed! ran far enough to be meaningful"
              ;; Without this the call assertion could pass on a seed! that
              ;; bridged and did nothing else.
              (is (= ["services.typesense.api-host"
                      "services.typesense.api-tls"
                      "services.typesense.collection-prefix"]
                     (:paths-written result))))

            (testing "and it called the env bridge for THIS tenant"
              (is (= 1 (count @calls))
                  (str "seed-config-from-env! was called " (count @calls)
                       " times. The documented supply route for "
                       "TYPESENSE_API_KEY_ADMIN is this call; without it the key "
                       "never reaches the demo tenant and materialization fails "
                       "with 'missing Typesense config: api-key-admin' (#494)."))
              (is (= demo-tenant/demo-tenant (second (first @calls)))
                  "the bridge was called for a different tenant"))

            (testing "and reports what it bridged, so a first run can see it"
              (is (= {:stubbed true} (:env-bridged result)))))))
      (finally (delete-test-db conn)))))

(deftest query-fails-in-the-unbridged-state-not-only-ingestion
  ;; SETTLING AN OPEN QUESTION RATHER THAN INHERITING A GUESS. #494 established
  ;; that INGESTION fails without the bridged key, and explicitly did not
  ;; establish whether QUERY does — the config had already been bridged by the
  ;; time it could have been tested.
  ;;
  ;; It does, and by the same route: every read in `digdir.rag.retrieval`
  ;; obtains its connection from `ts-utils/make-ts-settings`, which THROWS when
  ;; `:api-key-admin` is absent. No Typesense is needed to show it — the throw
  ;; happens before any request is built.
  (let [conn (create-test-db)]
    (try
      (seed-typesense-definitions! conn)
      ;; Exactly the pre-fix state: the authored non-secret values and no key.
      (seed-tenant-platform-node! conn demo-tenant/demo-tenant
                                  demo-tenant/typesense-platform-values)
      (with-accessor-context conn
        (testing "ingestion's resolver fails, as #494 observed"
          (let [e (try (ts-utils/make-ts-settings {:tenant demo-tenant/demo-tenant})
                       nil
                       (catch clojure.lang.ExceptionInfo ex ex))]
            (is (some? e) "settings resolved without a key — the premise is gone")
            (is (= :typesense/settings-unresolved (:kind (ex-data e))))
            (is (= [:api-key-admin] (:missing (ex-data e))))))

        (testing "AND a real retrieval read fails identically"
          (let [e (try (retrieval/lookup-search-phrases-similar
                        "phrases-coll" "docs-coll" ["hva er dette"] nil
                        {:tenant demo-tenant/demo-tenant})
                       nil
                       (catch clojure.lang.ExceptionInfo ex ex))]
            (is (some? e) "a retrieval read succeeded with no Typesense key")
            (is (= :typesense/settings-unresolved (:kind (ex-data e)))
                "query failed for some OTHER reason — this test would then prove nothing")
            (is (= [:api-key-admin] (:missing (ex-data e)))))))
      (finally (delete-test-db conn)))))

(deftest the-control-both-paths-succeed-once-the-key-is-present
  ;; The other direction. Without it, the test above passes on a corpus where
  ;; NOTHING resolves, and would keep passing if the key stopped mattering.
  (let [conn (create-test-db)]
    (try
      (seed-typesense-definitions! conn)
      (seed-tenant-platform-node! conn demo-tenant/demo-tenant
                                  (assoc demo-tenant/typesense-platform-values
                                         "services.typesense.api-key-admin" "a-key"))
      (with-accessor-context conn
        (testing "settings resolve once the environment has supplied the key"
          (let [settings (ts-utils/make-ts-settings {:tenant demo-tenant/demo-tenant})]
            (is (some? settings))
            (is (= "a-key" (:key settings))))))
      (finally (delete-test-db conn)))))
