(ns digdir.config.removed-definitions-test
  "Phase 4 of the provider-resolver change — the seven settable-but-inert definitions are gone from what a
   FRESH install gets: the code catalogue, the shipped snapshot, and therefore
   a store seeded from both.

   Phase 3 of the provider-resolver change took away every reader of these paths and left the definitions
   standing with \"RETIRED\" descriptions, so an operator could still set them to
   no effect. `bb config-set` and the admin UI show no description, which is
   the trap: a write that succeeds and changes nothing.

   ## What is pinned, and what is deliberately NOT

   ABSENCE, here, and NON-READERSHIP, in `provider-switch-reads-test`'s census
   of every place code names one of these paths.

   NOT what a read of one of these paths does on a fresh install. `cfg/get`
   throws on a missing definition, but that is a consequence of the removal,
   not the property wanted, and the two read APIs disagree about it:
   `get-platform-value-with-trace` returns the default instead. Pinning
   either answer would pin that disagreement as a contract.

   ## No retraction, by decision

   An install that predates this change keeps both the definitions and any
   values; nothing reads them, so they are harmless where they are. Only fresh
   installs differ. A retraction must never leave a value behind its
   definition: that is exactly the orphan that stops a database's own dumps
   importing. Retracting the values BEFORE the definitions avoids it.
   `remove-env-migrated-paths!` does that, in up to two transactions. The two
   pre-flight migrations (`retract-skills-retrieval-enabled-migration!`,
   `rerank-mode-split-migration!`) refuse on active values, then retract the
   definitions alone and leave a soft-deleted value without one. None of the
   three retracts values and definitions in one transaction. The skills
   pre-flight is a single transaction, but retracts no values. None handles the
   audit rows that name the path.

   That is also why the snapshot edit is safe, and why
   `the-shipped-snapshot-has-no-orphaned-value` is the guard that matters: the
   snapshot IS an export, and removing a definition from it while a value at
   that path stays behind would ship a dump the documented cold start cannot
   import.

   Values never appear here: paths and counts only."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.db :as config-db]
            [digdir.config.deployment-specific :as ds]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.config.schema :as config-schema]
            [digdir.data.db :as data-db]
            [digdir.import-export.system :as migration]
            [digdir.llm.provider-fixtures :as fx]
            [digdir.secrets :as secrets]
            [digdir.setup.config :as setup-config]))

(def ^:private removed-paths
  "Settable but read by nothing since Phase 3 of the provider-resolver change (the two keyword selectors
   and the LM Studio family) and the loader-fallback change (`services.openrouter.api-key`, whose last
   reader was the loader's OpenRouter route). The same seven are the needles of
   `provider-switch-reads-test`'s removed-path census."
  #{"services.search-phrases.provider" "services.self-improvement.provider"
    "services.lmstudio.api-key" "services.lmstudio.api-endpoint" "services.lmstudio.model"
    "services.openrouter.model" "services.openrouter.api-key"})

(def ^:private removed-env-vars
  "The env-bridge rows that seeded four of them."
  #{"LMSTUDIO_API_ENDPOINT" "LMSTUDIO_API_KEY" "LMSTUDIO_MODEL" "OPENROUTER_API_KEY"})

(def ^:private snapshot-path "../config/system-import.normalized.20260821.json")

(defn- snapshot []
  (let [f (io/file snapshot-path)]
    (when (.exists f) (json/parse-string (slurp f) true))))

(defn- present
  "The removed paths that `paths` still holds, sorted, for a failure message."
  [paths]
  (vec (sort (filter (set paths) removed-paths))))

;; ---------------------------------------------------------------------------
;; The two sources a fresh install is built from
;; ---------------------------------------------------------------------------

(deftest the-code-catalogue-registers-none-of-them
  (let [registered @fx/runtime-definitions]
    (testing "POSITIVE CONTROL: the capture sees registrations, including a sibling that stays"
      (is (<= 100 (count registered)) (str "only " (count registered) " registrations captured"))
      (is (contains? registered "services.self-improvement.model")
          "registered by the same function that registered services.self-improvement.provider"))
    (testing "ensure-all-config-definitions! registers none of the seven"
      (is (empty? (present registered))))))

(deftest the-shipped-snapshot-carries-none-of-them
  (let [paths (map :config-def/path (get-in (snapshot) [:data :definitions]))]
    (testing "POSITIVE CONTROL: the snapshot was read, by path"
      (is (<= 100 (count paths)) (str "only " (count paths) " definitions read from " snapshot-path))
      (is (some #{"services.self-improvement.model"} paths)))
    (testing "data.definitions carries none of the seven"
      (is (empty? (present paths))))))

(deftest the-shipped-snapshot-has-no-orphaned-value
  ;; The orphan-export trap. `import-node-values!` throws on a value whose
  ;; definition the target does not have, and by then the definitions and nodes
  ;; phases have already committed: one orphan leaves a half-imported store. An
  ;; export carries every definition of its database, so a real dump is only
  ;; orphaned if something removed a definition and left its value. The snapshot
  ;; is an export, and this PR removes definitions from it by hand - so this is
  ;; the check that such a removal left nothing behind. Self-contained, like any
  ;; export: a value must find its definition IN THE FILE.
  ;;
  ;; NOT redundant with the import's throw. When the build still registers the
  ;; path, boot supplies the definition and the import SUCCEEDS, so nothing else
  ;; sees the break: with this test disabled, removing a valued definition from
  ;; the snapshot passed the whole suite (the inert-config-definitions removal's sabotage).
  (let [data (:data (snapshot))
        defined (set (map :config-def/path (:definitions data)))
        valued (map :config.value/definition-path (:node-values data))]
    (testing "POSITIVE CONTROL: the snapshot carries values, so an orphan could exist to be found"
      (is (seq valued) "no node-values read - every assertion below would pass vacuously")
      (is (every? string? valued) "a node-value without a definition-path is an unreadable row"))
    (testing "every stored value has its definition in the snapshot"
      (is (empty? (remove defined valued))
          (str "values whose definition is not shipped: " (pr-str (vec (sort (distinct (remove defined valued))))))))
    (testing "and none of the seven holds a value"
      (is (empty? (present valued))))))

;; ---------------------------------------------------------------------------
;; What advertised them
;; ---------------------------------------------------------------------------

(deftest nothing-classifies-or-seeds-them
  (testing "POSITIVE CONTROL: each table is read, and holds a sibling that stays"
    (is (contains? ds/globally-defaultable-paths "services.self-improvement.model"))
    (is (contains? ds/deployment-specific-paths "services.llm.api-key"))
    (is (some #(= "OPENAI_API_KEY" (:env-var %)) env-bridge/env-config-bindings)))
  (testing "the deployment-specific decision table names none of them"
    (is (empty? (present ds/deployment-specific-paths)))
    (is (empty? (present ds/globally-defaultable-paths))))
  (testing "no env-bridge row seeds one, by path or by variable"
    (is (empty? (present (map :path env-bridge/env-config-bindings))))
    (is (empty? (filter removed-env-vars (map :env-var env-bridge/env-config-bindings)))))
  (testing ".env.example assigns none of their variables"
    ;; An assignment is a line that starts with the name. Comments may still
    ;; mention them; an operator copying the file gets no line to fill in.
    (let [lines (str/split-lines (slurp "../.env.example"))
          assigned (fn [v] (some #(str/starts-with? % (str v "=")) lines))]
      (is (assigned "MARKER_API_KEY") "POSITIVE CONTROL: a neighbouring optional variable is seen")
      (is (empty? (filter assigned (sort removed-env-vars)))))))

;; ---------------------------------------------------------------------------
;; A freshly seeded store
;; ---------------------------------------------------------------------------

(defn- create-db [id schema]
  (let [cfg {:store {:backend :mem :id (str id "-" (random-uuid))} :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema})
      conn)))

(defn- delete-db [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(deftest a-freshly-seeded-store-holds-none-of-them
  ;; The documented cold start (docs/onboarding.md §4): boot on an empty store,
  ;; then `bb migration-import` the shipped snapshot. The boot half is
  ;; `digdir.data.db/init-db!`'s config chain in its own order - one-shot
  ;; migrations, the definitions, post-definition migrations - replayed here
  ;; against a store of our own, because init-db! binds the process's store. The
  ;; import half is `bb migration-import`'s own call. The environment is empty,
  ;; so the import's env bridge writes nothing from whoever runs this.
  (let [config-conn (create-db "removed-defs-config" config-schema/config-migration-schema)
        main-conn (create-db "removed-defs-main" data-db/dh-schema)]
    (try
      (let [paths (with-redefs [config-db/get-conn (constantly config-conn)]
                    (binding [secrets/*env-lookup* (constantly nil)]
                      (with-out-str
                        (config-db/apply-one-shot-migrations! config-conn)
                        (setup-config/ensure-all-config-definitions!)
                        (config-db/apply-post-definition-migrations! config-conn)
                        (migration/import-from-file config-conn main-conn snapshot-path {:on-conflict :skip})))
                    (set (map :config-def/path (config-db/get-all-definitions @config-conn))))]
        (testing "POSITIVE CONTROL: both halves reached this store"
          (is (contains? paths "pipeline.chunks.split-max-length")
              "registered by boot and absent from the snapshot: boot ran here")
          (is (contains? paths "pipeline.generate.citations.always-verify")
              "carried by the snapshot and registered by no code: the import ran here"))
        (testing "the store holds none of the seven"
          (is (empty? (present paths)))))
      (finally
        (delete-db config-conn)
        (delete-db main-conn)))))
