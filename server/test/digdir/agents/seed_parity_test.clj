(ns digdir.agents.seed-parity-test
  "A bb task and a server boot must seed the same agents.

   They used not to. `digdir.skills.api/initialize!` registered 5 skill
   graphs and `digdir.skills.init/initialize!` registered 15; every bb-task
   JVM reached only the first, so `seed-builtin-agents!` filtered agents
   against a smaller registry and persisted narrowed — or dropped — records.
   See #85, and #81 for the misdiagnosis it caused.

   These tests pin the two entry points together. If they are ever split
   again, the first test fails with a concrete diff of the agent sets."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datahike.api :as d]
            [digdir.agents.core :as agents-core]
            [digdir.agents.db :as agents-db]
            [digdir.config.db :as config-db]
            [digdir.skills.api :as skills-api]
            [digdir.skills.init :as skills-init]))

(defn- restore-skills
  "Leave the registry populated for whatever runs next in the suite."
  [f]
  (f)
  (skills-init/ensure-initialized!))

(use-fixtures :each restore-skills)

(defn- create-test-db
  []
  (let [cfg {:store {:backend :mem
                     :id (str "seed-parity-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (d/connect cfg)))

(defn- delete-test-db
  [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn- comparable
  "Agent records minus the fields that legitimately differ between two runs."
  [agents]
  (->> agents
       (map #(dissoc % :created-at :updated-at))
       (sort-by :id)
       vec))

(defn- seed-through
  "Clear the registry, initialise through `init-fn`, seed a fresh DB, read back.

   Returns {:graph-count n :agents [...]}."
  [init-fn]
  (skills-init/reset-skills!)
  (init-fn)
  (let [conn (create-test-db)]
    (try
      (config-db/ensure-schema! conn)
      (agents-db/seed-builtin-agents! conn)
      {:graph-count (count (agents-core/available-skill-graph-ids))
       :agents (comparable (agents-db/list-agents @conn))}
      (finally
        (delete-test-db conn)))))

(deftest bb-task-and-server-boot-seed-the-same-agents
  (testing "the two initialisation entry points produce one agent set"
    ;; What a bb task JVM reaches: agents.db calls skills-api/initialize!.
    (let [via-task (seed-through skills-api/initialize!)
          ;; What the server runs at boot: src-dev/dev.cljc calls this one.
          via-boot (seed-through skills-init/initialize!)]

      (is (= (set (map :id (:agents via-task)))
             (set (map :id (:agents via-boot))))
          "same agent ids")

      (is (= (:agents via-task) (:agents via-boot))
          "same agent records, field for field — not merely the same ids")

      (is (= (:graph-count via-task) (:graph-count via-boot))
          "both entry points see the same skill-graph registry")

      (is (seq (:agents via-task))
          "the comparison is not vacuous — agents were actually seeded"))))

(deftest seeding-does-not-rewrite-agents-whose-graphs-all-resolve
  (testing "an agent whose declared graphs are all registered is stored verbatim"
    (skills-init/reset-skills!)
    (skills-api/initialize!)
    (let [available (agents-core/available-skill-graph-ids)
          conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (agents-db/seed-builtin-agents! conn)
        (let [resolvable (->> (agents-core/builtin-agent-definitions)
                              (filter (fn [{:keys [allowed-skill-graphs default-skill-graph]}]
                                        (and (every? available allowed-skill-graphs)
                                             (contains? available default-skill-graph)))))]
          (is (seq resolvable)
              "at least one builtin agent should have all its graphs registered")
          (doseq [{:keys [id default-skill-graph allowed-skill-graphs]} resolvable]
            (let [seeded (agents-db/get-agent @conn id)]
              (is (some? seeded)
                  (str id " should be seeded, not skipped"))
              (is (= default-skill-graph (:default-skill-graph seeded))
                  (str id " default must not be substituted"))
              (is (= (set allowed-skill-graphs) (set (:allowed-skill-graphs seeded)))
                  (str id " allowed graphs must not be dropped")))))
        (finally
          (delete-test-db conn))))))

(deftest docs-agent-graphs-resolve-under-the-task-entry-point
  (testing "builtin/docs-agent — the agent #85 was found through — survives seeding"
    ;; Guarded rather than asserted outright: docs-agent is defined in
    ;; src-dev, so a build without it on the classpath legitimately has no
    ;; such agent. The :test alias does include src-dev.
    (skills-init/reset-skills!)
    (skills-api/initialize!)
    (when-let [definition (->> (agents-core/builtin-agent-definitions)
                               (filter #(= "builtin/docs-agent" (:id %)))
                               first)]
      (let [conn (create-test-db)]
        (try
          (config-db/ensure-schema! conn)
          (agents-db/seed-builtin-agents! conn)
          (let [seeded (agents-db/get-agent @conn "builtin/docs-agent")]
            (is (some? seeded)
                "docs-agent must not be dropped by a task-path seed")
            (is (= (:default-skill-graph definition) (:default-skill-graph seeded))
                "docs-agent must keep its declared default, not the first survivor")
            (is (= (count (:allowed-skill-graphs definition))
                   (count (:allowed-skill-graphs seeded)))
                "docs-agent must keep every declared graph"))
          (finally
            (delete-test-db conn)))))))
