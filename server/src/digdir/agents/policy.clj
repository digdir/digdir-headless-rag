(ns digdir.agents.policy
  "Policy helpers for resolved agent definitions."
  (:require [digdir.agents.core :as agents]))

(defn enabled?
  "True when the agent is enabled for use."
  [agent]
  (true? (:enabled? (agents/normalize-agent agent))))

(defn allowed-skill-graph-ids
  "Return the allowed skill graph IDs for an agent as a set of durable strings."
  [agent]
  (-> agent
      agents/normalize-agent
      :allowed-skill-graphs
      set))

(defn allows-skill-graph?
  "True when the agent allows the given skill graph."
  [agent skill-graph-id]
  (contains? (allowed-skill-graph-ids agent)
             (agents/normalize-skill-graph-id skill-graph-id)))

(defn allowed-dataset-scopes
  "Return the canonical allowed dataset scopes for an agent."
  [agent]
  (:allowed-dataset-scopes (agents/normalize-agent agent)))

(defn allows-dataset-scope?
  "True when the agent allows the provided dataset scope."
  [agent dataset-scope]
  (let [candidate (agents/normalize-dataset-scope dataset-scope)
        allowed-set (set (map agents/dataset-scope-key (allowed-dataset-scopes agent)))]
    (contains? allowed-set (agents/dataset-scope-key candidate))))

(defn resolve-execution-policy
  "Return a compact execution policy view for an agent."
  [agent]
  (let [agent (agents/normalize-agent agent)]
    {:agent-id (:id agent)
     :default-skill-graph (:default-skill-graph agent)
     :allowed-skill-graphs (allowed-skill-graph-ids agent)
     :allowed-dataset-scopes (allowed-dataset-scopes agent)
     :guardrails (:guardrails agent)
     :enabled? (:enabled? agent)}))
