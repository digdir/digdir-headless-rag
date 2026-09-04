(ns digdir.config.deployment-specific-test
  "`:deployment-specific?` — the declaration, its enforcement, and the round trip.

   A path is deployment-specific when there is no correct global default, so a
   shipped value is always wrong for somebody. Three things have to hold, and
   they fail in different ways:

     1. EVERY `services.*` path is decided — a new service must force an answer
        rather than inherit one by omission.
     2. `__global__` cannot hold a value for such a path — enforced at write.
     3. The flag SURVIVES EXPORT AND IMPORT as a boolean. This is the one that
        does not announce itself: `build-definition-tx-data` silently drops any
        field it does not destructure, exactly as `definition-keyword-fields`
        silently stringifies an unregistered keyword. Both are proven by running
        a round trip rather than by reading the code."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.db :as config-db]
            [digdir.config.deployment-specific :as ds]
            [digdir.config.ops.sync :as sync]
            [digdir.config.schema :as schema]))

(def ^:private snapshot-path "../config/system-import.normalized.20260821.json")

(defn- snapshot []
  (let [f (io/file snapshot-path)]
    (when (.exists f) (json/parse-string (slurp f) true))))

(defn- create-test-db []
  (let [cfg {:store {:backend :mem :id (str "ds-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn- delete-test-db [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn) (d/delete-database cfg)))

;;; ---------------------------------------------------------------------------
;;; 1. Every services.* path is decided
;;; ---------------------------------------------------------------------------

(deftest every-services-path-is-decided
  (let [snap (snapshot)
        paths (->> (get-in snap [:data :definitions])
                   (map :config-def/path)
                   (filter some?))]

    (testing "the instrument can answer"
      (is (some? snap) (str "snapshot not found at " snapshot-path))
      (is (<= 100 (count paths))
          (str "expected a populated definition list, saw " (count paths)))
      (is (<= 30 (count (filter ds/decision-required? paths)))
          "expected many services.* paths; a rename would empty this guard"))

    (testing "no services.* path is undecided"
      ;; Absence from BOTH sets is what "undecided" means — which is why
      ;; `globally-defaultable-paths` is written out rather than left implicit.
      ;; If absence meant "not deployment-specific", a new service would inherit
      ;; an answer, which is the whole thing this prevents.
      (let [undecided (ds/undecided-paths paths)]
        (is (empty? undecided)
            (str "these services.* paths are in neither set, so nobody has "
                 "decided whether they have a global default: " (pr-str undecided)))))

    (testing "and the two sets do not overlap"
      (is (empty? (set/intersection ds/deployment-specific-paths
                                    ds/globally-defaultable-paths))))))

(deftest the-declaration-is-not-vacuous
  (testing "both sets are populated"
    (is (<= 10 (count ds/deployment-specific-paths)))
    (is (<= 10 (count ds/globally-defaultable-paths))))
  (testing "the predicate discriminates rather than answering one way"
    (is (ds/deployment-specific? "services.typesense.api-host"))
    (is (not (ds/deployment-specific? "services.typesense.api-tls")))
    (is (not (ds/deployment-specific? "skills.retrieval.top-k"))
        "a tuning value has a correct global default and must not be marked")))

;;; ---------------------------------------------------------------------------
;;; 2. The snapshot carries no deployment-specific value
;;;    (this SUBSUMES the heuristic guard in snapshot-endpoints-test)
;;; ---------------------------------------------------------------------------

(deftest snapshot-carries-no-deployment-specific-value
  (let [nvs (get-in (snapshot) [:data :node-values])]

    (testing "the instrument can SEE a value"
      ;; ⚠️ RE-ANCHORED, NOT RELAXED. This control used to assert that a
      ;; corpus-source URL (`pipeline.source.website.base-url`) was present —
      ;; and those values lived on DIGDIR nodes. When the product stopped
      ;; shipping that tenant, this control failed: the guard written to catch
      ;; "the search cannot see anything" was itself anchored on data that went
      ;; away. The temptation at that point is to weaken the control, which
      ;; would leave the absence-assertion below proving nothing forever.
      ;;
      ;; So it is anchored on two things that do not depend on which tenants
      ;; ship: the definition list, and the existence of ANY non-blank value.
      ;; The second is the one that matters — the assertion below is about a
      ;; non-blank value at a path, so the control has to prove a non-blank
      ;; value is findable at all.
      (is (<= 100 (count (get-in (snapshot) [:data :definitions])))
          "the reader cannot see the definition list; everything below is vacuous")
      (is (<= 5 (count (remove #(str/blank? (str (:config.value/raw %))) nvs)))
          (str "no non-blank node-value found, so 'no deployment-specific path "
               "carries a value' would pass even if one did")))

    (testing "no deployment-specific path carries a value"
      ;; Blank is not a value: it ships nothing. `services.auth.admin-user-emails`
      ;; is present-and-empty in the snapshot and is deliberately not an offence.
      (let [offenders (->> nvs
                           (filter #(ds/deployment-specific? (:config.value/definition-path %)))
                           (filter #(not (str/blank? (str (:config.value/raw %)))))
                           (map (juxt :config.value/definition-path :config.value/tenant))
                           sort vec)]
        (is (empty? offenders)
            (str "the committed snapshot ships values for paths that have no "
                 "correct global default: " (pr-str offenders)))))))

(deftest no-path-is-both-inherit-owned-and-deployment-specific
  ;; TWO INDEPENDENTLY-MAINTAINED DECLARATIONS THAT MUST NEVER DISAGREE.
  ;;
  ;; `:config-def/ownership :inherit` says a path HAS a live global baseline
  ;; tenants override. `:deployment-specific?` says a path HAS NO correct global
  ;; default. Marking one path both ways asserts that it both has and has not a
  ;; global default — a contradiction, and one no reviewer would catch, because
  ;; the two declarations live in different files written years apart.
  ;;
  ;; ⚠️ THIS IS PROTECTION, NOT CORROBORATION, and the distinction is worth
  ;; stating because it was initially got wrong. They agree on all seven paths
  ;; today; this test does not PROVE that agreement, it PRESERVES it. The
  ;; failure it guards is the recurring one in this codebase — two doors into
  ;; one room, correct at whichever door the diff happened to touch.
  (let [snap (snapshot)
        defs (get-in snap [:data :definitions])
        inherit-owned (->> defs
                           (filter #(= "inherit" (str (:config-def/ownership %))))
                           (map :config-def/path)
                           (filter ds/decision-required?)
                           set)]

    (testing "the instrument can SEE the other declaration"
      ;; An absence proves nothing if the needle cannot hit: were the field
      ;; renamed, or read with the wrong key, this set would be empty and the
      ;; assertion below would pass forever.
      (is (<= 5 (count inherit-owned))
          (str "expected several inherit-owned services.* paths, saw "
               (count inherit-owned)
               " — if this is 0 the contradiction check is vacuous")))

    (testing "no path is declared both ways"
      (let [contradictory (set/intersection inherit-owned ds/deployment-specific-paths)]
        (is (empty? contradictory)
            (str "these paths are declared BOTH :ownership :inherit (has a global "
                 "baseline) AND deployment-specific (has no global default), "
                 "which cannot both be true: " (pr-str (sort contradictory))))))

    (testing "and every inherit-owned path is positively accounted for"
      ;; Stronger than the absence above: each one must appear in the
      ;; globally-defaultable set, so a path cannot satisfy this test merely by
      ;; being undecided.
      (is (empty? (set/difference inherit-owned ds/globally-defaultable-paths))
          (str "inherit-owned but not recorded as globally defaultable: "
               (pr-str (sort (set/difference inherit-owned
                                             ds/globally-defaultable-paths))))))))

;;; ---------------------------------------------------------------------------
;;; 3. Enforced at write
;;; ---------------------------------------------------------------------------

(defn- seed-def! [conn path deployment-specific?]
  (config-db/upsert-definition! conn {:path path
                                      :root :platform
                                      :value-type :string
                                      :deployment-specific? deployment-specific?}))

(defn- seed-node! [conn tenant node-id]
  (config-db/create-config-node! conn {:root :platform :tenant tenant
                                       :node-id node-id :label tenant
                                       :tenant-config-key "default"}))

(deftest global-value-refused-for-a-deployment-specific-path
  (let [conn (create-test-db)]
    (try
      (seed-def! conn "services.example.api-host" true)
      (seed-def! conn "services.example.tuning" false)
      (seed-node! conn "__global__" "platform-global")
      (seed-node! conn "acme" "platform-acme")

      (testing "the CONTROL: a non-deployment-specific path IS allowed globally"
        ;; Without this the test could pass because writes fail for some other
        ;; reason entirely.
        (is (#{:created :updated}
             (config-db/set-node-value! conn {:root :platform :tenant "__global__"
                                              :node-id "platform-global"
                                              :path "services.example.tuning"
                                              :value "fine" :master-key nil}))))

      (testing "a deployment-specific path is REFUSED at __global__"
        (let [e (try (config-db/set-node-value!
                       conn {:root :platform :tenant "__global__"
                             :node-id "platform-global"
                             :path "services.example.api-host"
                             :value "some-host:8108" :master-key nil})
                     nil
                     (catch clojure.lang.ExceptionInfo ex ex))]
          (is (some? e) "a __global__ write for a deployment-specific path must throw")
          (is (= :deployment-specific-has-no-global-default (:reason (ex-data e)))
              "and must say WHY, not merely fail")))

      (testing "but the same path is fine on a TENANT node — the point is the default, not the value"
        (is (#{:created :updated}
             (config-db/set-node-value! conn {:root :platform :tenant "acme"
                                              :node-id "platform-acme"
                                              :path "services.example.api-host"
                                              :value "acme-host:8108" :master-key nil}))))
      (finally (delete-test-db conn)))))

;;; ---------------------------------------------------------------------------
;;; 4. The round trip — the step that does not announce itself
;;; ---------------------------------------------------------------------------

(deftest deployment-specific-survives-export-and-import
  (let [conn (create-test-db)]
    (try
      (seed-def! conn "services.example.api-host" true)
      (seed-def! conn "services.example.tuning" false)

      (let [exported (->> (config-db/get-all-definitions @conn)
                          (mapv #(dissoc % :db/id)))
            ;; Through JSON, because that is what an export IS. A field that
            ;; survives an in-memory round trip and dies in serialisation would
            ;; pass a weaker test.
            round-tripped (-> exported
                              (json/generate-string)
                              (json/parse-string true))
            ;; The real import-side deserializer, reached through its var
            ;; because it is private — a re-implementation here would test my
            ;; copy of the logic rather than the one imports actually run.
            reimported (mapv #'sync/deserialize-definition round-tripped)
            by-path (into {} (map (juxt :path identity)) reimported)]

        (testing "the export is not empty — otherwise everything below is vacuous"
          (is (= 2 (count exported)))
          (is (= 2 (count reimported))))

        (testing "the flag survives as a BOOLEAN, not a string"
          (let [v (get-in by-path ["services.example.api-host" :deployment-specific?])]
            (is (true? v)
                (str "expected true, got " (pr-str v)
                     " — a string here is the silent-stringification failure "
                     "`definition-keyword-fields` exists to prevent, arriving "
                     "in the boolean lane"))))

        (testing "and false survives as false rather than being dropped"
          (let [v (get-in by-path ["services.example.tuning" :deployment-specific?])]
            (is (false? v) (str "expected false, got " (pr-str v)))))

        (testing "re-upserting the imported definitions preserves the flag"
          ;; The full loop: the field has to survive `build-definition-tx-data`,
          ;; which silently drops anything it does not destructure.
          (let [conn2 (create-test-db)]
            (try
              (doseq [dfn reimported]
                (config-db/upsert-definition! conn2 dfn))
              (let [again (into {} (map (juxt :config-def/path identity))
                                (config-db/get-all-definitions @conn2))]
                (is (true? (get-in again ["services.example.api-host"
                                          :config-def/deployment-specific?]))
                    "the flag was dropped on re-import — check the destructuring in build-definition-tx-data")
                (is (false? (get-in again ["services.example.tuning"
                                           :config-def/deployment-specific?]))))
              (finally (delete-test-db conn2))))))
      (finally (delete-test-db conn)))))
