(ns digdir.config.conn-isolation-test
  "`with-redefs` of `digdir.data.db/get-conn` isolates a test's config work.

   `config-db/get-conn` used to return a process-global override FIRST, and boot
   set that override to the main connection (`init-db!`) and never cleared it.
   So once anything in a test JVM had touched the real store, every later
   `with-redefs` of the main connection was silently ignored for config work: it
   landed on the real store instead of the test's own connection. Nothing at the
   call site said so, and the failures surfaced far away, only in suite runs.

   Pinned here:
   - boot leaves no override behind;
   - after boot, redefining the main connection isolates config work;
   - an override that DISAGREES with a redefined main connection is refused,
     naming where it was set, instead of silently winning;
   - no false alarm: an agreeing override, a bound `*conn*`, and an override
     with no redefinition (the CLI reset in `setup/common`) all still work.

   The dynamic var and the canonical `get-conn` are reached through `resolve`,
   so this namespace also LOADS against code that lacks them. That makes the
   red-first run a real failure, not a namespace that failed to load."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.db :as config-db]
            [digdir.config.schema :as config-schema]
            [digdir.data.db :as db]
            [digdir.setup.config :as setup-config]))

(defn- mem-conn []
  (let [cfg {:store {:backend :mem :id (str "conn-isolation-" (random-uuid))} :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data config-schema/config-migration-schema})
      conn)))

(defn- drop-conn [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn- override [] @@(resolve 'digdir.config.db/!config-conn))

(defn- override-set-at [] (some-> (resolve 'digdir.config.db/!config-conn-set-at) deref deref))

(deftest boot-leaves-no-process-global-config-override
  ;; `db/get-conn` runs `init-db!` if nothing has yet. Either way, boot has run
  ;; in this JVM when the assertion runs. The override must be unset: boot binds
  ;; its config connection for its own extent rather than pinning it.
  ;; This also detects a LEAK: an earlier namespace that set the override and
  ;; did not restore it turns this red, and the message names where it was set.
  (is (some? (db/get-conn)) "POSITIVE CONTROL: the real store is up, so boot has run")
  (is (nil? (override))
      (str "a process-global config override is set after boot. Set at: " (pr-str (override-set-at)))))

(deftest redefining-the-main-connection-isolates-config-work-after-boot
  ;; The state that used to defeat isolation: boot has run, so the override was
  ;; pinned to the real store. A redefinition must now win for config work.
  (db/get-conn)
  (let [conn (mem-conn)
        path "x.conn-isolation.probe"]
    (try
      (with-redefs [db/get-conn (constantly conn)]
        (binding [*out* (java.io.StringWriter.)]
          (setup-config/ensure-config-definition! path {:root :platform :value-type :string})))
      (testing "the definition landed on the test's own connection"
        (is (some? (config-db/get-definition @conn path))))
      (testing "and NOT on the real store"
        (is (nil? (config-db/get-definition @(db/get-conn) path))))
      (finally (drop-conn conn)))))

(deftest an-override-that-disagrees-with-a-redefined-main-connection-is-refused
  ;; Restores the RAW previous override, never `get-conn`'s result and never a
  ;; blanket nil. Red-first found nil-ing wrong: it cleared the override boot had
  ;; pinned, and the isolation test above then passed on the unfixed code
  ;; whenever this test happened to run before it.
  ;; It restores the PROVENANCE raw too, not through `set-conn!`. Sabotage found
  ;; that `set-conn!` re-records its caller, so a restore through it reported an
  ;; earlier namespace's leak under THIS test's name.
  (let [a (mem-conn) b (mem-conn)
        conn-atom @(resolve 'digdir.config.db/!config-conn)
        at-var (resolve 'digdir.config.db/!config-conn-set-at)
        previous @conn-atom
        previous-at (some-> at-var deref deref)]
    (try
      (config-db/set-conn! a)
      (testing "the redefinition returns a different connection: refused, naming the setter"
        (with-redefs [db/get-conn (constantly b)]
          (let [e (try (config-db/get-conn) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e) "refused, instead of silently returning the override")
            (is (re-find #"^config-db/get-conn: DELIBERATE ISOLATION GUARD, not a harness" (str (some-> e ex-message)))
                "its FIRST line says it is a deliberate guard, not a harness fault. A fixture that hits it aborts the run, and this line is what the log ends on")
            ;; Pin what is PRINTED, not what is recorded: the message shows the FIRST
            ;; recorded frame. Checking `some` of the recorded frames stayed green when
            ;; set-conn! stopped filtering its own frame and the message printed
            ;; `digdir.config.db$set_conn_BANG_`, which names nobody.
            (is (re-find #"set by config-db/set-conn! at digdir\.config\.conn_isolation_test" (str (some-> e ex-message)))
                (str "the refusal PRINTS this test as the setter: " (some-> e ex-message))))))
      (testing "no false alarm: an override that AGREES with the redefinition is returned"
        (with-redefs [db/get-conn (constantly a)]
          (is (identical? a (config-db/get-conn)))))
      (testing "no false alarm: an override with NO redefinition is returned (the CLI reset's case)"
        (is (identical? a (config-db/get-conn))))
      (finally
        (reset! conn-atom previous)
        (when at-var (reset! @at-var previous-at))
        (drop-conn a)
        (drop-conn b)))))

(deftest a-bound-config-connection-wins-and-cannot-leak
  (let [a (mem-conn) b (mem-conn)
        conn-var (resolve 'digdir.config.db/*conn*)]
    (try
      (is (some? conn-var) "config-db/*conn* exists")
      (when conn-var
        (with-redefs [db/get-conn (constantly a)]
          (with-bindings {conn-var b}
            (is (identical? b (config-db/get-conn)) "the binding wins over the main connection")))
        (is (nil? (override)) "and leaves no override behind"))
      (finally
        (drop-conn a)
        (drop-conn b)))))
