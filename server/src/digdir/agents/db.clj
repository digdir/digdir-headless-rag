(ns digdir.agents.db
  "Persistence for durable agent definitions stored in the config DB."
  (:require [clojure.edn :as edn]
            [datahike.api :as d]
            [nano-id.core :refer [nano-id]]
            [taoensso.telemere :as t]
            [digdir.agents.core :as agents]
            ;; Deliberately digdir.skills.init and NOT digdir.skills.api:
            ;; api/initialize! registers the builtin skills and graphs only
            ;; (5 graphs), while init/ensure-initialized! also runs the demo
            ;; and src-dev registrations (15). Seeding and validating against
            ;; the smaller registry is what made a bb task and a server boot
            ;; produce different agent sets — see #85, and #81 for the
            ;; misdiagnosis that followed. No cycle: nothing under
            ;; digdir.skills.* or digdir.demo.* requires this namespace back.
            [digdir.skills.init :as skills-init]))

(defn- agent-eid
  [db agent-id]
  (d/q '[:find ?e .
         :in $ ?agent-id
         :where
         [?e :agent/id ?agent-id]]
       db agent-id))

(defn- agent-pull
  [db agent-id]
  (d/q '[:find (pull ?e [:agent/id
                         :agent/name
                         :agent/description
                         :agent/instructions
                         :agent/default-skill-graph
                         :agent/allowed-skill-graphs
                         :agent/guardrails
                         :agent/skill-params
                         :agent/enabled?
                         :agent/created-at
                         :agent/updated-at
                         {:agent/allowed-dataset-scopes
                          [:agent.dataset-scope/id
                           :agent.dataset-scope/tenant
                           :agent.dataset-scope/dataset-config-key]}]) .
         :in $ ?agent-id
         :where
         [?e :agent/id ?agent-id]]
       db agent-id))

(defn- normalize-stored-agent
  [agent-entity]
  (when agent-entity
    (let [dataset-scopes (:agent/allowed-dataset-scopes agent-entity)
          dataset-scope->scope (fn [dataset-scope]
                                 {:tenant (:agent.dataset-scope/tenant dataset-scope)
                                  :dataset-config-key (:agent.dataset-scope/dataset-config-key dataset-scope)})]
      (agents/normalize-agent
       {:id (:agent/id agent-entity)
        :name (:agent/name agent-entity)
        :description (:agent/description agent-entity)
        :instructions (:agent/instructions agent-entity)
        :default-skill-graph (:agent/default-skill-graph agent-entity)
        :allowed-skill-graphs (vec (:agent/allowed-skill-graphs agent-entity))
        :allowed-dataset-scopes (mapv dataset-scope->scope dataset-scopes)
        :guardrails (if-let [guardrails-str (:agent/guardrails agent-entity)]
                      (edn/read-string guardrails-str)
                      {})
        :skill-params (if-let [skill-params-str (:agent/skill-params agent-entity)]
                        ;; Decode defensively: a malformed EDN payload on a
                        ;; single agent shouldn't break the agent lookup or
                        ;; cascade into "no agents available" errors. Fall
                        ;; back to {} and trust the caller to validate when
                        ;; it actually consumes the skill-params.
                        (try (edn/read-string skill-params-str)
                             (catch Exception _ {}))
                        {})
        :enabled? (:agent/enabled? agent-entity)
        :created-at (:agent/created-at agent-entity)
        :updated-at (:agent/updated-at agent-entity)}))))

(defn get-agent
  "Load a normalized agent definition by ID."
  [db agent-id]
  (some-> (agent-pull db agent-id)
          normalize-stored-agent))

(defn list-agents
  "List normalized agent definitions sorted by agent ID."
  [db]
  (->> (d/q '[:find [?agent-id ...]
              :where
              [?e :agent/id ?agent-id]]
            db)
       sort
       (mapv #(get-agent db %))))

(defn list-enabled-agents
  "List normalized enabled agent definitions."
  [db]
  (->> (list-agents db)
       (filter :enabled?)
       vec))

(defn- existing-dataset-scope-eids
  [db agent-id]
  (vec (d/q '[:find [?dataset-scope ...]
              :in $ ?agent-id
              :where
              [?e :agent/id ?agent-id]
              [?e :agent/allowed-dataset-scopes ?dataset-scope]]
            db agent-id)))

(defn make-dataset-scope-checker
  "Returns a fn that resolves an agent dataset-scope against the live config DB.
   The fn answers truthy iff config-db/get-dataset-by-ref returns a record for
   the {tenant + dataset-config-key} pair. Wraps the lookup in a broad catch
   because the underlying resolver throws on unknown refs.

   This is **opt-in** strictness — callers that want the agent's
   `:allowed-dataset-scopes` validated against the live registry pass the
   returned fn as `:dataset-scope-checker` to `validate-agent` or
   `upsert-agent!`. Useful in the admin/UI agent-upsert flow where a
   wrong-form key (e.g. `\"digdir/public-docs\"` instead of `\"public-docs\"`)
   would otherwise pass schema validation but silently drop the agent from
   playground dropdowns.

   Not constructed automatically by `upsert-agent!` because test fixtures
   often use scopes that intentionally don't resolve in their minimal test
   DBs; strict-by-default would force every such test to opt out explicitly."
  [conn]
  (fn [{:keys [tenant dataset-config-key]}]
    (try
      (require 'digdir.config.db 'digdir.config.core)
      (let [get-by-ref (resolve 'digdir.config.db/get-dataset-by-ref)
            get-master-key (resolve 'digdir.config.core/get-master-key)]
        (some? (get-by-ref @conn
                           {:tenant tenant :dataset-config-key dataset-config-key}
                           (get-master-key))))
      (catch Throwable _ false))))

(defn upsert-agent!
  "Create or update a durable agent definition.

   Accepts an optional `:dataset-scope-checker` in the 3-arg arity for strict
   validation of `:allowed-dataset-scopes`. Build the checker with
   `make-dataset-scope-checker`. Omitted by default to keep test fixtures
   working — production paths that care (admin API, demo seeding for
   production-like envs) should pass it explicitly."
  ([conn agent]
   (skills-init/ensure-initialized!)
   (upsert-agent! conn agent {:available-skill-graphs (agents/available-skill-graph-ids)}))
  ([conn agent {:keys [available-skill-graphs dataset-scope-checker]}]
   ;; Capture timestamps BEFORE validate-agent!/normalize-agent — those
   ;; helpers project onto a canonical shape and drop everything else.
   (let [input-created-at (or (:created-at agent) (:agent/created-at agent))
         input-updated-at (or (:updated-at agent) (:agent/updated-at agent))
         agent (agents/validate-agent! agent {:available-skill-graphs available-skill-graphs
                                              :dataset-scope-checker dataset-scope-checker})
         db @conn
         now (System/currentTimeMillis)
         existing (get-agent db (:id agent))
         ;; Input wins so dump-import overrides whatever init-time
         ;; seed-builtin-agents! pre-stamped with `now`. Operator/UI flows
         ;; pass no timestamps and fall through to the existing-or-now path.
         updated-at (or input-updated-at (when existing (:updated-at existing)) now)
         created-at (or input-created-at (when existing (:created-at existing)) now)
         dataset-scope-tx (mapv (fn [{:keys [tenant dataset-config-key]}]
                                  (let [scope-id (str "agent-dataset-scope-" (nano-id))]
                                    {:db/id scope-id
                                     :agent.dataset-scope/id scope-id
                                     :agent.dataset-scope/tenant tenant
                                     :agent.dataset-scope/dataset-config-key dataset-config-key}))
                                (:allowed-dataset-scopes agent))
         dataset-scope-ids (mapv :db/id dataset-scope-tx)
         retracts (mapv (fn [eid] [:db/retractEntity eid])
                        (existing-dataset-scope-eids db (:id agent)))
         ;; Cardinality-many: asserting alone would only ever add.
         existing-eid (agent-eid db (:id agent))
         graph-retracts (when existing-eid
                          (mapv (fn [graph-id]
                                  [:db/retract existing-eid
                                   :agent/allowed-skill-graphs graph-id])
                                (d/q '[:find [?graph-id ...]
                                       :in $ ?e
                                       :where [?e :agent/allowed-skill-graphs ?graph-id]]
                                     db existing-eid)))
         tx-data (vec
                  (concat
                   retracts
                   graph-retracts
                   dataset-scope-tx
                   [(cond-> {:agent/id (:id agent)
                             :agent/name (:name agent)
                             :agent/description (:description agent)
                             :agent/instructions (:instructions agent)
                             :agent/default-skill-graph (:default-skill-graph agent)
                             :agent/allowed-skill-graphs (vec (:allowed-skill-graphs agent))
                             :agent/guardrails (pr-str (:guardrails agent))
                             :agent/enabled? (:enabled? agent)
                             :agent/created-at created-at
                             :agent/updated-at updated-at}
                      ;; Only write :skill-params when the agent actually
                      ;; carries overrides. An empty map writes an empty
                      ;; "{}" string that round-trips fine, but the missing
                      ;; attr case is the more common path (most agents have
                      ;; no overrides) and skipping the assoc keeps the tx
                      ;; minimal for those.
                      (seq (:skill-params agent))
                      (assoc :agent/skill-params (pr-str (:skill-params agent)))
                      (seq dataset-scope-ids) (assoc :agent/allowed-dataset-scopes dataset-scope-ids))]))]
     (d/transact conn {:tx-data tx-data})
     (get-agent @conn (:id agent)))))

(defn delete-agent!
  "Delete an agent definition and its dataset-scope children."
  [conn agent-id]
  (let [db @conn
        eid (agent-eid db agent-id)
        dataset-scope-eids (existing-dataset-scope-eids db agent-id)]
    (when eid
      (d/transact conn {:tx-data (vec (concat
                                       (map (fn [dataset-scope-eid] [:db/retractEntity dataset-scope-eid])
                                            dataset-scope-eids)
                                       [[:db/retractEntity eid]]))})
      true)))

(defn- filter-to-available-skill-graphs
  "Drop unknown skill graphs from an agent's :allowed-skill-graphs and
   :default-skill-graph so it stays valid even when some referenced
   graphs (e.g. src-dev-only :docs/self-improve-*) are absent from the
   current classpath. Production validation still rejects an agent
   that has no allowed graphs after filtering.

   Three behaviours, and only two of them are honest degradation (#92):

     dropping unavailable graphs from :allowed-skill-graphs
       The record becomes a truthful SUBSET of what was declared. Logged :warn.

     skipping an agent left with no graphs at all
       Truthful ABSENCE. This case is load-bearing: a production build has no
       src-dev graphs, and filtering is what lets it seed the remaining agents
       instead of failing boot over an agent it could never run. Handled by
       the caller, seed-builtin-agents!.

     substituting the default with (first filtered-allowed)  -- REMOVED
       This one was different in kind. The other two leave a record that is
       true but reduced; substitution persisted an agent asserting a default
       it never declared, and nothing about the result looks wrong. An agent
       running a different graph than its definition says is not a degraded
       version of that agent.

   An unavailable default now yields NO default, logged at :error. The agent is
   still seeded and its surviving graphs are still selectable explicitly, so
   capability is not discarded to make a strictness point; what goes away is the
   false claim. A nil default is a supported state - every default-related rule
   in agents.core/validate-agent! is guarded on the default being present.

   Note on #81: that mystery surfaced as an Unknown-default-skill-graph error,
   which
   validate-agent! raises for a default that is NOT available. Substitution
   always chose an available graph, so it cannot itself have produced that
   message; the likelier source is a record persisted where the graph existed
   and validated where it did not. Recorded because the substitution and the
   #81 symptom are adjacent but not the same mechanism."
  [agent available-skill-graphs]
  (let [declared-allowed (:allowed-skill-graphs agent)
        filtered-allowed (filterv available-skill-graphs declared-allowed)
        dropped (vec (remove available-skill-graphs declared-allowed))
        default (:default-skill-graph agent)
        default-available? (contains? available-skill-graphs default)
        ;; Unavailable default -> NO default, rather than someone else's graph.
        ;; See the docstring: dropping is a truthful subset and skipping is a
        ;; truthful absence, but substituting makes the record assert something
        ;; the definition never said. A nil default is a supported state - every
        ;; default-related rule in validate-agent! is guarded on the default
        ;; being present - so this degrades rather than invents.
        filtered-default (when default-available? default)]
    (when (seq dropped)
      (t/log! :warn [::agent-allowed-skill-graphs-dropped
                     {:agent-id (:id agent)
                      :dropped dropped
                      :kept filtered-allowed}]))
    (when (and (some? default) (not default-available?))
      (t/log! :error [::agent-default-skill-graph-unavailable
                      {:agent-id (:id agent)
                       :declared-default default
                       :kept-allowed filtered-allowed
                       :effect "seeded without a default; explicit graph selection still works"}]))
    (assoc agent
           :allowed-skill-graphs filtered-allowed
           :default-skill-graph filtered-default)))

(defn- seed-one-agent!
  "Filter one definition to the available graphs and persist it."
  [conn agent-def available-skill-graphs]
  (let [filtered (filter-to-available-skill-graphs agent-def available-skill-graphs)]
    (if (seq (:allowed-skill-graphs filtered))
      (upsert-agent! conn filtered {:available-skill-graphs available-skill-graphs})
      (do (t/log! :warn [::agent-skipped-no-available-skill-graphs
                         {:agent-id (:id agent-def)
                          :declared-skill-graphs (:allowed-skill-graphs agent-def)}])
          nil))))

(defn seed-builtin-agents!
  "Seed builtin agent definitions for the current product surfaces.
   This is idempotent because agents are upserted by :agent/id.

   Filters each agent's allowed/default skill graphs against the
   currently-registered set, so an agent that lists src-dev-only
   graphs in its allowed list (e.g. :builtin/docs-agent referencing
   :docs/self-improve-*) still seeds cleanly in production builds
   that exclude src-dev. Agents that have no valid skill graphs
   after filtering are skipped — this happens in narrow test scopes
   where the demo namespaces haven't been required."
  [conn]
  (skills-init/ensure-initialized!)
  (let [available-skill-graphs (agents/available-skill-graph-ids)]
    (vec
     (keep #(seed-one-agent! conn % available-skill-graphs)
           (agents/builtin-agent-definitions)))))

(defn seed-agent!
  "Reseed one builtin agent from its code definition."
  [conn agent-id]
  (skills-init/ensure-initialized!)
  (let [available-skill-graphs (agents/available-skill-graph-ids)]
    (when-let [agent-def (first (filter #(= agent-id (:id %))
                                        (agents/builtin-agent-definitions)))]
      (seed-one-agent! conn agent-def available-skill-graphs))))

(defn drift-report
  "What a reseed would do to every agent, stored or declared."
  [db]
  ;; skills-init, not skills.api: seeding resolves against the larger registry.
  (skills-init/ensure-initialized!)
  (let [available (agents/available-skill-graph-ids)
        by-id #(into {} (map (juxt :id identity)) %)
        stored (by-id (list-agents db))
        declared (by-id (agents/builtin-agent-definitions))]
    (->> (into (set (keys stored)) (keys declared))
         sort
         (mapv (fn [agent-id]
                 (assoc (agents/agent-drift {:stored (get stored agent-id)
                                             :declared (get declared agent-id)
                                             :available-skill-graphs available})
                        :stored (get stored agent-id)
                        :declared (get declared agent-id)))))))
