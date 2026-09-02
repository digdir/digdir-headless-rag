(ns digdir.import-export.entities.agents
  "Agent import/export helpers."
  (:require [digdir.agents.db :as agents-db]
            [digdir.import-export.report :as report]))

(defn export-agents
  [db]
  (agents-db/list-agents db))

(defn import-agents!
  [conn agents]
  (doseq [agent agents]
    (agents-db/upsert-agent! conn agent))
  {:imported (count agents)})

(defn preview-import-agents
  [db agents on-conflict]
  (report/preview-existing-items agents
                                 #(some? (agents-db/get-agent db (:id %)))
                                 on-conflict))
