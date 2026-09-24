(ns digdir.boot.agents
  "Reconcile each builtin's stored skill graphs with its definition at boot."
  (:require [taoensso.telemere :as t]
            [digdir.agents.db :as agents-db]
            [digdir.config.db :as config-db]))

(defn seed!
  "Reconcile the builtin agents' skill graphs. nil when there is no config DB."
  []
  (try
    (if-let [conn (config-db/get-conn)]
      (let [seeded (agents-db/reconcile-skill-graphs! conn)]
        (t/log! :info [::builtin-agents-reconciled {:count (count seeded)
                                                :agent-ids (mapv :id seeded)}])
        (mapv :id seeded))
      (do (t/log! :warn [::no-config-db {:effect "builtin agents not reconciled"}])
          nil))
    (catch Exception e
      (t/log! :error [::seed-failed {:error (ex-message e)
                                     :effect "boot continues with the stored agent rows"}])
      nil)))
