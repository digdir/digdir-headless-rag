(ns digdir.boot.agents
  "Reconcile the stored agent rows with the code definitions at boot."
  (:require [taoensso.telemere :as t]
            [digdir.agents.db :as agents-db]
            [digdir.config.db :as config-db]))

(defn seed!
  "Reseed the builtin agents. nil when there is no config DB."
  []
  (try
    (if-let [conn (config-db/get-conn)]
      (let [seeded (agents-db/seed-builtin-agents! conn)]
        (t/log! :info [::builtin-agents-seeded {:count (count seeded)
                                                :agent-ids (mapv :id seeded)}])
        (mapv :id seeded))
      (do (t/log! :warn [::no-config-db {:effect "builtin agents not reconciled"}])
          nil))
    (catch Exception e
      (t/log! :error [::seed-failed {:error (ex-message e)
                                     :effect "boot continues with the stored agent rows"}])
      nil)))
