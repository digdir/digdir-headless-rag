(ns digdir.skills.builtin.agent
  "Agent skill stub - Proxies to decomposed namespaces."
  (:require [digdir.skills.builtin.agent.core :as core]))

;; Proxy registration and metadata for system compatibility
(def agent-metadata core/agent-metadata)
(def agent-skill core/agent-skill)
(def agent-tool-definition core/agent-tool-definition)

(defn register!
  "Register the agent skill (proxied)."
  []
  (core/register!))
