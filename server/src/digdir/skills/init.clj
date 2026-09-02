(ns digdir.skills.init
  "Skill system initialization.

   This namespace provides functions to initialize the skill system,
   register all built-in skills and skill graphs, and verify the system
   is ready for use."
  (:require [clojure.set :as set]
            [digdir.rag.skills.core :as skills-core]
            [digdir.skills.templates.core :as templates-core]
            ;; Graph structure guards (#240 follow-up, #268). Safe direction:
            ;; neither namespace requires digdir.skills.init back.
            [digdir.skills.graph.schema :as schema]
            [digdir.skills.graph.runner :as runner]
            ;; Built-in skills
            [digdir.skills.builtin.retrieval :as retrieval]
            [digdir.skills.builtin.rerank :as rerank]
            [digdir.skills.builtin.synthesis :as synthesis]
            [digdir.skills.builtin.query-planner :as query-planner]
            [digdir.skills.builtin.entity-extraction :as entity-extraction]
            [digdir.skills.builtin.fact-checking :as fact-checking]
            [digdir.skills.builtin.summarization :as summarization]
            [digdir.skills.builtin.multi-retrieval :as multi-retrieval]
            [digdir.skills.builtin.graph-builder :as graph-builder]
            [digdir.skills.builtin.agent :as agent]
            [digdir.skills.builtin.overview :as overview]
            [digdir.skills.builtin.agent.tools :as agent-tools]
            [digdir.skills.builtin.agent.graphs :as agent-graphs]
            [digdir.skills.builtin.agent.iteration-bundled :as agent-iter-bundled]
            [digdir.skills.builtin.agent.iteration-faithful :as agent-iter-faithful]
            ;; Built-in skill graphs
            [digdir.skills.templates.builtin :as builtin-templates]
            ;; Demo scenario tool registrations (S7 — Altinn authoring)
            [digdir.demo.altinn-authoring :as altinn-authoring]
            [digdir.demo.altinn-translation-drift :as altinn-translation-drift]
            ;; Demo scenario tool registrations (S2 — Altinn release-notes cross-check)
            [digdir.demo.altinn-release-notes :as altinn-release-notes]
            ;; Promoted enrichment path (slice 1 of #82, see #89)
            [digdir.skills.enrichment.questions-graph :as questions-graph]
            ;; Builtin agent definitions — the source of truth for which skill
            ;; graphs must resolve. Safe direction: digdir.agents.core depends on
            ;; digdir.skills.api, and nothing under digdir.skills.* depends back
            ;; on digdir.agents.*, so this does not close a cycle.
            [digdir.agents.core :as agents-core]
            ;; Database background worker
            [digdir.data.background-worker :as bg-worker]))

;; =============================================================================
;; Initialization State
;; =============================================================================

(defonce ^{:private true
           :doc "Tracks initialization state"}
  !init-state
  (atom {:initialized false
         :skills-registered 0
         :skill-graphs-registered 0
         :initialized-at nil}))

;; =============================================================================
;; Skill Registration
;; =============================================================================

(def builtin-skill-namespaces
  "List of built-in skill namespaces with their register functions."
  [{:ns 'digdir.skills.builtin.retrieval
    :skill-id :builtin/retrieval
    :register-fn retrieval/register!}
   {:ns 'digdir.skills.builtin.rerank
    :skill-id :builtin/rerank
    :register-fn rerank/register!}
   {:ns 'digdir.skills.builtin.synthesis
    :skill-id :builtin/synthesis
    :register-fn synthesis/register!}
   {:ns 'digdir.skills.builtin.query-planner
    :skill-id :builtin/query-planner
    :register-fn query-planner/register!}
   {:ns 'digdir.skills.builtin.entity-extraction
    :skill-id :builtin/entity-extraction
    :register-fn entity-extraction/register!}
   {:ns 'digdir.skills.builtin.fact-checking
    :skill-id :builtin/fact-checking
    :register-fn fact-checking/register!}
   {:ns 'digdir.skills.builtin.summarization
    :skill-id :builtin/summarization
    :register-fn summarization/register!}
   {:ns 'digdir.skills.builtin.multi-retrieval
    :skill-id :builtin/multi-retrieval
    :register-fn multi-retrieval/register!}
   {:ns 'digdir.skills.builtin.graph-builder
    :skill-id :builtin/graph-builder
    :register-fn graph-builder/register!}
   {:ns 'digdir.skills.builtin.agent
    :skill-id :builtin/agent
    :register-fn agent/register!}
   ;; Phase 2.1: agent's per-tool dispatch as a first-class skill so its
   ;; execution flows through skills/execute-skill (duration tracking,
   ;; error-wrapping, registry-level observability) instead of being a thin
   ;; imperative wrapper inside the ReAct loop.
   {:ns 'digdir.skills.builtin.agent.tools
    :skill-id :builtin/agent-tool-call
    :register-fn agent-tools/register!}
   ;; Phase 2.5: graph-composition orchestration skills + two outer
   ;; skill-graphs (bundled, faithful). The inner sub-graphs the outer
   ;; graphs reference are registered in 2.5.C / 2.5.D; until those land,
   ;; the outer graphs are registered but unsafe to execute.
   {:ns 'digdir.skills.builtin.agent.graphs
    :skill-id :builtin/agent-setup
    :register-fn agent-graphs/register!}
   ;; Phase 2.5.C: bundled inner sub-graph (4 bundle skills +
   ;; :builtin/agent-iteration-bundled).
   {:ns 'digdir.skills.builtin.agent.iteration-bundled
    :skill-id :builtin/agent-bundle-llm-and-tools
    :register-fn agent-iter-bundled/register!}
   ;; Phase 2.5.D: faithful inner sub-graph (10 step skills +
   ;; 6 route-branch skills + :builtin/agent-iteration-faithful).
   {:ns 'digdir.skills.builtin.agent.iteration-faithful
    :skill-id :builtin/agent-llm-call
    :register-fn agent-iter-faithful/register!}
   ;; #240: AI-overview replacement for the retired :builtin/simple-qa. Like
   ;; agent.graphs above, its register! registers both its skills and the
   ;; skill graph they compose (:builtin/ai-overview).
   {:ns 'digdir.skills.builtin.overview
    :skill-id :builtin/overview-evidence-gate
    :register-fn overview/register!}])

(defn register-builtin-skills!
  "Register all built-in skills.

   Returns: Count of skills registered"
  []
  (doseq [{:keys [register-fn]} builtin-skill-namespaces]
    (try
      (register-fn)
      (catch Exception e
        (println "Warning: Failed to register skill:" (.getMessage e)))))
  (count (skills-core/list-skills)))

;; =============================================================================
;; Skill Graph Registration
;; =============================================================================

(defn load-optional-dev-namespace!
  "Load a src-dev-only namespace and invoke its registration fn.

   Absence is EXPECTED on a production classpath — src-dev is not shipped —
   and is reported as such. Every OTHER failure propagates.

   That distinction is the point. The previous handler caught `Exception`
   broadly and printed identical text whether the namespace was legitimately
   absent or present and genuinely broken, so a real defect was
   indistinguishable from normal production output (#132). It is the same
   swallowing pattern that kept #71 invisible, in the same file.

   `load!` and `resolve-fn` are injectable so the present-but-throws case can
   be tested. That case is the one the old code hid, so it is the one that
   needs a test.

   Returns :registered, :no-register-fn, or :absent."
  ([ns-sym fn-name label]
   (load-optional-dev-namespace! ns-sym fn-name label require resolve))
  ([ns-sym fn-name label load! resolve-fn]
   (try
     (load! ns-sym)
     (if-let [reg (resolve-fn (symbol (name ns-sym) (name fn-name)))]
       (do (reg) :registered)
       :no-register-fn)
     (catch java.io.FileNotFoundException _
       ;; Reads as expected, because it is: production has no src-dev.
       (println (str "Note: " label " not registered — " ns-sym
                     " is absent, as expected on a production classpath."))
       :absent))))

(defn register-builtin-skill-graphs!
  "Register all built-in skill graphs.

   Returns: Count of skill graphs registered"
  []
  (builtin-templates/register-all!)
  (count (templates-core/list-skill-graphs)))

;; =============================================================================
;; Initialization
;; =============================================================================

(declare check-skill-graphs)
(declare check-graph-skills)
(declare check-graph-conditions)
(declare check-graph-declared-outputs)
;; ensure-initialized! (below) falls back to reinitialize!, which is defined
;; after it.
(declare reinitialize!)

(defn initialize!
  "Initialize the skill system.

   Registers all built-in skills and skill graphs.
   Safe to call multiple times - will only initialize once.

   Returns: Initialization state map"
  []
  (if (:initialized @!init-state)
    @!init-state
    (do
      ;; Start the background worker for non-blocking DB transactions
      (bg-worker/start!)

      (let [skills-count (register-builtin-skills!)
            skill-graphs-count (register-builtin-skill-graphs!)
            ;; Demo-scenario registrations (tool + skill + skill graph). Idempotent.
            _ (altinn-authoring/register!)
            _ (altinn-release-notes/register!)
            _ (altinn-translation-drift/register!)
            ;; Promoted per-chunk enrichment sub-graph (:docs/enrich-one-chunk).
            ;; Explicit and idempotent for the same reason as the demos: a
            ;; load-only registration cannot survive skills-api/reset-skills!.
            _ (questions-graph/register!)
            ;; self-improve-agent (Phase C) lives in src-dev/; use a
            ;; try/require so production builds without src-dev on the
            ;; classpath silently skip it (mirrors the seed-agents
            ;; pattern in digdir.config.db).
            _ (load-optional-dev-namespace!
                'digdir.demo.self-improve-agent 'register!
                "self-improve-agent tools")
            ;; Re-register the src-dev-only skill graphs that src-dev agents
            ;; name. Loading digdir.agents.dev registers them once, but a
            ;; second require after skills-api/reset-skills! is a no-op, so
            ;; they need an explicit idempotent call here — exactly like the
            ;; src/ demo graphs above. Absent in production builds.
            _ (load-optional-dev-namespace!
                'digdir.agents.dev 'register-graphs!
                "src-dev skill graphs")
            state {:initialized true
                   :skills-registered skills-count
                   :skill-graphs-registered skill-graphs-count
                   :initialized-at (System/currentTimeMillis)}]
        (reset! !init-state state)
        ;; Boot-time cross-check. Registration of the src-dev-only graphs is a
        ;; warn-and-continue `require`, so a missing graph is otherwise silent
        ;; until someone actually invokes the agent (issue #71). Report, do not
        ;; throw: refusing to boot would take the whole service down for what
        ;; may be one unusable agent.
        (let [{:keys [ok missing]} (check-skill-graphs)]
          (when-not ok
            (println (str "WARNING: " (count missing)
                          " skill graph(s) referenced by builtin agents are NOT registered:"))
            (doseq [m missing]
              (println (str "  - " m)))
            (println (str "  Agents naming these graphs will fail at invocation time. "
                          "If they are src-dev-only graphs, this build cannot serve them."))))
        ;; Next layer down (#91): a graph can be registered while the skills it
        ;; steps through are not, which fails at invocation instead of here.
        ;; Report rather than throw, for the same reason as above.
        (let [{:keys [ok missing]} (check-graph-skills)]
          (when-not ok
            (println (str "WARNING: " (count missing)
                          " registered skill graph step(s) name a skill that is NOT registered:"))
            (doseq [{:keys [graph skill]} missing]
              (println (str "  - " graph " -> " skill)))
            (println "  These graphs will fail at invocation time.")))
        ;; Layer below that (#240): a step's :condition is a dependency the
        ;; topological sort cannot see, because edges come from :inputs refs
        ;; only. A conditioned step that does not consume what it reads can be
        ;; scheduled first, and the condition then evaluates against an absent
        ;; value — no crash, no warning, just a uniformly wrong branch.
        (let [{:keys [ok problems]} (check-graph-conditions)]
          (when-not ok
            (println (str "WARNING: " (count problems)
                          " conditioned graph step(s) depend on a step not ordered before them:"))
            (doseq [{:keys [graph step detail]} problems]
              (println (str "  - " graph " / " step ": " detail)))
            (println "  These graphs will take the wrong branch silently.")))
        ;; And #268: a declared :output no step produces. Harmless to execution
        ;; — collect-outputs never reads the declared list — but digdir.skills.ui
        ;; renders it, so it advertises an output callers never receive.
        (let [{:keys [ok problems]} (check-graph-declared-outputs)]
          (when-not ok
            (println (str "WARNING: " (count problems)
                          " skill graph(s) declare :outputs that no step produces:"))
            (doseq [{:keys [graph unexpected stale-exemptions]} problems]
              (when (seq unexpected)
                (println (str "  - " graph " declares " (pr-str unexpected)
                              " which no step produces")))
              (when (seq stale-exemptions)
                (println (str "  - " graph " has stale exemption(s) " (pr-str stale-exemptions)
                              " — the graph is fixed; delete them from"
                              " digdir.skills.init/declared-output-exemptions"))))))
        state))))

(defn ensure-initialized!
  "Ensure the system is initialized, initializing if needed.

   Also re-initializes when the state atom says \"initialized\" but the
   registry is empty. There are two initialisation state atoms — this one and
   `digdir.skills.api`'s — and `skills-api/reset-skills!` clears the shared
   registries without touching this one. Trusting the flag alone would hand
   back an empty registry, which is how a seed after such a reset ended up
   registering nothing (#85). \"Ensure\" has to mean the registry is actually
   populated, not that someone once said it was.

   Returns: true"
  []
  (when (or (not (:initialized @!init-state))
            (empty? (templates-core/list-skill-graphs)))
    (reinitialize!))
  true)

(defn reset-skills!
  "Reset the skill system to uninitialized state.

   Clears all registrations. Useful for testing."
  []
  (skills-core/clear-registry!)
  (templates-core/clear-registry!)
  (reset! !init-state {:initialized false
                       :skills-registered 0
                       :skill-graphs-registered 0
                       :initialized-at nil}))

(defn reinitialize!
  "Force re-initialization of the skill system.

   Returns: New initialization state"
  []
  (reset-skills!)
  (initialize!))

;; =============================================================================
;; Status & Verification
;; =============================================================================

(defn status
  "Get current initialization status.

   Returns: Status map with initialization info and counts"
  []
  (let [state @!init-state]
    (if (:initialized state)
      (assoc state
             :current-skills (count (skills-core/list-skills))
             :current-skill-graphs (count (templates-core/list-skill-graphs))
             :skill-ids (vec (skills-core/list-skill-ids))
             :skill-graph-ids (vec (templates-core/list-skill-graph-ids)))
      state)))

(defn verify-skills
  "Verify all expected skills are registered.

   Returns: Map with :ok and :missing keys"
  []
  (ensure-initialized!)
  (let [expected-ids (set (map :skill-id builtin-skill-namespaces))
        registered-ids (set (skills-core/list-skill-ids))
        missing (set/difference expected-ids registered-ids)]
    (if (empty? missing)
      {:ok true :count (count registered-ids)}
      {:ok false :missing (vec missing) :count (count registered-ids)})))

(defn expected-skill-graph-ids
  "Every skill-graph id the builtin agent definitions promise will resolve —
   each agent's :default-skill-graph plus every entry of its
   :allowed-skill-graphs — as durable strings.

   Derived from `builtin-agent-definitions` rather than hardcoded. The
   previous hardcoded set of three could not notice a fourth graph going
   missing, which is how issue #71 shipped: `builtin/docs-agent` names
   `docs/self-improve-graph` as its default, that graph is defined only
   under src-dev/, and production builds have no src-dev on the classpath."
  []
  (->> (agents-core/builtin-agent-definitions)
       (mapcat (fn [agent]
                 (cons (:default-skill-graph agent)
                       (:allowed-skill-graphs agent))))
       (remove nil?)
       (map agents-core/normalize-skill-graph-id)
       set))

(defn- check-skill-graphs
  "Pure check: compare expected ids against the registry as it stands.

   Deliberately does NOT call `ensure-initialized!`, so `initialize!` can
   use it after registration without re-entering itself."
  []
  (let [expected (expected-skill-graph-ids)
        ;; Registry ids are keywords; agent definitions carry strings.
        ;; Normalize both sides before comparing.
        registered (into #{} (map agents-core/normalize-skill-graph-id)
                         (templates-core/list-skill-graph-ids))
        missing (set/difference expected registered)]
    (if (empty? missing)
      {:ok true :count (count registered) :expected (count expected)}
      {:ok false
       :missing (vec (sort missing))
       :count (count registered)
       :expected (count expected)})))

(defn step-skill-ids
  "Every skill-id a graph step references, at any depth.

   Handles the regular, :foreach, :loop and :select shapes. A :sub-graph step
   contributes nothing — the sub-graph validates its own steps when it
   registers, and it is checked here in its own right."
  [step]
  (cond
    (:skill step) [(:skill step)]
    (:foreach step) (when-let [s (-> step :do :skill)] [s])
    (:loop step) (when-let [s (-> step :do :skill)] [s])
    (:select step) (->> (:branches step) vals (keep #(-> % :do :skill)))
    :else nil))

(defn graph-skill-ids
  "Every skill-id a registered skill-graph map steps through."
  [skill-graph]
  (->> (get-in skill-graph [:graph :steps])
       (mapcat step-skill-ids)
       (remove nil?)
       distinct))

(defn- check-graph-skills
  "Pure check: every registered graph's steps name a registered skill.

   The #71 check validates that the graphs builtin agents name are registered.
   This is the next layer down — that the skills those graphs step through are
   registered too. Without it a graph can register while its skills cannot
   resolve, and the failure surfaces at invocation instead of registration.

   Deliberately does NOT call `ensure-initialized!`, so `initialize!` can use it
   after registration without re-entering itself."
  []
  (let [missing (for [graph (templates-core/list-skill-graphs)
                      skill-id (graph-skill-ids graph)
                      :when (nil? (skills-core/get-skill skill-id))]
                  {:graph (:id graph) :skill skill-id})]
    {:ok (empty? missing)
     :missing (vec missing)
     :checked (count (templates-core/list-skill-graphs))}))

;; =============================================================================
;; Guard: a step's :condition must depend only on steps ordered before it
;; =============================================================================

(def ^:private inert-probe
  "Truthy stand-in returned for every lookup BELOW the top level.

   Returning something truthy rather than nil matters: a condition like
   `(and (get-in outs [:a :outputs :x]) (get-in outs [:b :outputs :y]))`
   short-circuits on a nil `:a` and never looks up `:b`, so the probe would
   record a partial dependency set and the guard would pass a step it should
   have caught."
  (reify clojure.lang.ILookup
    (valAt [this _] this)
    (valAt [this _ _] this)))

(defn- condition-probe
  "A stand-in for the `step-outputs` map that records which step-ids a
   condition function looks up.

   `digdir.skills.graph.runner/topological-sort` builds edges from
   `schema/extract-input-refs` only, and `:condition` contributes none — so a
   condition function is a dependency the scheduler cannot see. Static analysis
   cannot see inside a function either, which is why this is a probe rather
   than a parser: call the condition with a recording `ILookup` and read back
   the keys it touched.

   ONLY the top level records. `step-outputs` is `{step-id result}`, so a
   depth-1 key is a step id and everything deeper is inside one step's result
   map. A probe that recorded at every depth would report
   `[:gate :outputs :evidence-sufficient?]` as three dependencies, two of which
   are not steps at all, and then fail a graph that is correctly wired."
  [seen]
  (reify clojure.lang.ILookup
    (valAt [_ k] (swap! seen conj k) inert-probe)
    (valAt [_ k _] (swap! seen conj k) inert-probe)))

(defn condition-step-deps
  "Step-ids a step's `:condition` depends on, or `:opaque`.

   A keyword condition is resolved by `runner/resolve-input-ref`: `:$foo` is a
   graph input and depends on no step, anything else names a step. A function
   condition is probed. A function that throws under probing returns `:opaque`
   — declare `:condition-deps` on the step to say what it reads.

   `:opaque` is deliberately NOT treated as \"no dependencies\". A guard whose
   unprobeable case passes would be counted as coverage while covering
   nothing."
  [step]
  (if-let [declared (:condition-deps step)]
    (set declared)
    (let [c (:condition step)]
      (cond
        (nil? c) #{}
        (keyword? c) (if (schema/graph-input? c) #{} #{c})
        ;; #348: same rule as a keyword, applied to the ref's head. A vector
        ;; ref is fully transparent to this guard — unlike a fn, which has to
        ;; be probed and can come back :opaque.
        (vector? c) (let [head (first c)]
                      (if (or (nil? head) (schema/graph-input? head)) #{} #{head}))
        (fn? c) (let [seen (atom #{})]
                  (try
                    (c (condition-probe (atom #{})) (condition-probe seen))
                    @seen
                    (catch Throwable _ :opaque)))
        :else #{}))))

(defn- transitive-input-deps
  "Every step guaranteed to run before `step-id`, following real input edges."
  [deps step-id]
  (loop [frontier (set (get deps step-id [])) seen #{}]
    (if (empty? frontier)
      seen
      (let [next-id (first frontier)]
        (recur (into (disj frontier next-id) (remove seen (get deps next-id [])))
               (conj seen next-id))))))

(defn condition-problems-for-graph
  "Conditioned steps in ONE registered skill-graph map that depend on a step
   not ordered before them.

   The failure this prevents is not a crash. A step whose condition reads a
   sibling it does not consume can be scheduled before that sibling, and the
   condition then evaluates against an absent value — uniformly, plausibly and
   wrongly, while the graph reads correctly in source. That is how
   :builtin/ai-overview declined every single overview before #240 caught it by
   executing the graph rather than reviewing it.

   Satisfied transitively: a dependency two steps upstream is still guaranteed.

   Takes a skill-graph rather than reading the registry so the guard can be
   tested against a deliberately broken graph without registering one."
  [skill-graph]
  (let [graph (:graph skill-graph)]
    (when (map? graph)
      (let [deps (runner/build-dependency-graph graph)]
        (->> (:steps graph)
             (filter :condition)
             (keep
               (fn [step]
                 (let [condition-deps (condition-step-deps step)]
                   (if (= :opaque condition-deps)
                     {:graph (:id skill-graph) :step (:id step)
                      :reason :opaque-condition
                      :detail "Condition function threw under probing. Declare :condition-deps on the step."}
                     (let [guaranteed (transitive-input-deps deps (:id step))
                           unguaranteed (vec (remove guaranteed condition-deps))]
                       (when (seq unguaranteed)
                         {:graph (:id skill-graph) :step (:id step)
                          :reason :condition-dep-not-consumed
                          :detail (str "Condition reads " (pr-str (vec condition-deps))
                                       " but nothing orders " (pr-str unguaranteed)
                                       " before this step. Consume what the condition reads.")}))))))
             vec)))))

(defn- check-graph-conditions
  "Pure check: every conditioned step also *consumes* what its condition reads.

   Deliberately does NOT call `ensure-initialized!`, so `initialize!` can use it
   after registration without re-entering itself."
  []
  (let [problems (mapcat condition-problems-for-graph (templates-core/list-skill-graphs))]
    {:ok (empty? problems)
     :problems (vec problems)
     :checked (count (templates-core/list-skill-graphs))}))

;; =============================================================================
;; Guard: a graph's declared :outputs must be produced by one of its steps
;; =============================================================================

(def ^:private declared-output-exemptions
  "Graphs with a declared `:output` no step produces, known at the time this
   guard landed. Tracked in #268.

   An allowlist rots the moment it outlives what it excuses, so
   `check-graph-declared-outputs` reports a STALE entry as a failure too: fix
   the graph and the guard tells you to delete the exemption. Do not add to
   this map to make a new violation pass.

   That makes the list self-verifying in both directions — too broad fails as
   stale, too narrow fails as unexpected — which is also why an entry here is
   evidence the defect is real rather than an artefact of how
   `step-produced-outputs` computes the produced set.

   Entries are only consulted for graphs actually in the registry, so an
   exemption naming a src-dev-only graph cannot fail a production boot where
   that graph is absent. All three below are registered from src/."
  {:builtin/fact-checker      #{:verification :evidence}
   :docs/enrich-one-chunk     #{:proposal :verify}
   :docs/outline-graph        #{:search-phrases}})

(defn step-produced-outputs
  "Output keys a step can contribute to the graph's collected outputs.

   `runner`'s `collect-outputs` merges every successful step's outputs, so a
   step contributes its skill's declared `:outputs` — plus, for a sub-graph or
   loop body, the inner graph's, and the `:collect-as` key a loop binds."
  [step]
  (let [skill-ids (cond
                    (:skill step) [(:skill step)]
                    (:foreach step) (keep identity [(-> step :do :skill)])
                    (:loop step) (keep identity [(-> step :do :skill)])
                    (:select step) (->> (:branches step) vals (keep #(-> % :do :skill)))
                    :else nil)
        graph-ids (cond
                    (:sub-graph step) [(-> step :sub-graph :graph-id)]
                    (:loop step) (keep identity [(-> step :do :sub-graph :graph-id)])
                    (:foreach step) (keep identity [(-> step :do :sub-graph :graph-id)])
                    :else nil)]
    (into (set (keep identity [(:collect-as step)]))
          (concat
            (mapcat #(:outputs (:metadata (skills-core/get-skill %))) skill-ids)
            (mapcat #(:outputs (:graph (templates-core/get-skill-graph %))) graph-ids)))))

(defn declared-output-problems-for-graph
  "Declared `:outputs` of ONE registered skill-graph map that no step produces,
   plus any exemption that no longer describes a real violation.

   `collect-outputs` never consults the declared list, so a wrong entry does
   not break execution — it misinforms. `digdir.skills.ui` renders exactly this
   list to a human, so a stale key is a graph advertising an output a caller
   will never receive. :docs/outline-graph declaring :search-phrases is a name
   that lost its rename; :docs/enrich-one-chunk declaring :verify is a step id
   in a slot that takes output keys.

   `exemptions` is the set excused for this graph. Takes both explicitly so the
   guard can be tested without reaching into the registry or the exemption map."
  [skill-graph exemptions]
  (let [graph (:graph skill-graph)]
    (when (map? graph)
      (let [produced (into #{} (mapcat step-produced-outputs (:steps graph)))
            exempt (set exemptions)
            orphans (into #{} (remove produced) (:outputs graph))
            unexpected (vec (sort (remove exempt orphans)))
            ;; An exemption that no longer describes a violation is itself a
            ;; failure — otherwise the list outlives the problem and quietly
            ;; blesses whatever reuses those names later.
            stale (vec (sort (remove orphans exempt)))]
        (when (or (seq unexpected) (seq stale))
          {:graph (:id skill-graph)
           :unexpected unexpected
           :stale-exemptions stale})))))

(defn- check-graph-declared-outputs
  "Pure check: every key in a graph's `:outputs` is produced by some step.

   Deliberately does NOT call `ensure-initialized!`, so `initialize!` can use it
   after registration without re-entering itself."
  []
  (let [problems (keep #(declared-output-problems-for-graph
                          % (get declared-output-exemptions (:id %) #{}))
                       (templates-core/list-skill-graphs))]
    {:ok (empty? problems)
     :problems (vec problems)
     :checked (count (templates-core/list-skill-graphs))}))

(defn verify-graph-conditions
  "Verify no conditioned step depends on a step that is not ordered before it.

   Returns: {:ok bool :problems [{:graph :step :reason :detail}] :checked n}"
  []
  (ensure-initialized!)
  (check-graph-conditions))

(defn verify-graph-declared-outputs
  "Verify every graph's declared :outputs are produced by one of its steps.

   Returns: {:ok bool :problems [{:graph :unexpected :stale-exemptions}] :checked n}"
  []
  (ensure-initialized!)
  (check-graph-declared-outputs))

(defn verify-graph-skills
  "Verify every skill stepped through by a registered skill graph is itself
   registered.

   Returns: {:ok bool :missing [{:graph :skill}] :checked n}"
  []
  (ensure-initialized!)
  (check-graph-skills))

(defn verify-skill-graphs
  "Verify every skill graph referenced by a builtin agent is registered.

   Returns: Map with :ok, :missing, :count and :expected keys"
  []
  (ensure-initialized!)
  (check-skill-graphs))

(defn health-check
  "Perform a health check on the skill system.

   Returns: Health status map"
  []
  (let [skills-status (verify-skills)
        skill-graphs-status (verify-skill-graphs)]
    {:healthy (and (:ok skills-status) (:ok skill-graphs-status))
     :skills skills-status
     :skill-graphs skill-graphs-status
     :initialized (:initialized @!init-state)}))

(comment
  ;; Initialize the system
  (initialize!)

  ;; Check status
  (status)

  ;; Verify everything is registered
  (health-check)

  ;; Reset for testing
  (reset-skills!)

  ;; Force re-initialization
  (reinitialize!))
