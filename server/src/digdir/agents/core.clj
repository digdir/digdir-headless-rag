(ns digdir.agents.core
  "Core agent model, validation, and builtin definitions."
  (:require [clojure.string :as str]
            [digdir.skills.api :as skills-api]))

(declare builtin-agent-definitions)

(defn normalize-skill-graph-id
  "Normalize a skill graph identifier to durable string form."
  [skill-graph-id]
  (cond
    (keyword? skill-graph-id) (if-let [graph-ns (namespace skill-graph-id)]
                                (str graph-ns "/" (name skill-graph-id))
                                (name skill-graph-id))
    (string? skill-graph-id) skill-graph-id
    :else nil))

(defn normalize-dataset-scope
  "Normalize a dataset scope to canonical keyword keys."
  [dataset-scope]
  (let [tenant (or (:tenant dataset-scope) (get dataset-scope "tenant"))
        dataset-config-key (or (:dataset-config-key dataset-scope) (get dataset-scope "dataset_config_key")
                               (get dataset-scope "dataset-config-key")
                               (:tenant-config-key dataset-scope) (get dataset-scope "tenant_config_key")
                               (get dataset-scope "tenant-config-key")
                               (:config-key dataset-scope) (get dataset-scope "config_key"))]
    {:tenant tenant
     :dataset-config-key dataset-config-key}))

(defn dataset-scope-key
  "Stable tuple key for a canonical dataset scope."
  [{:keys [tenant dataset-config-key]}]
  [tenant dataset-config-key])

(defn distinct-dataset-scopes
  "Remove duplicate dataset scopes while preserving order."
  [dataset-scopes]
  (let [seen (volatile! #{})]
    (reduce (fn [acc dataset-scope]
              (let [scope-key (dataset-scope-key dataset-scope)]
                (if (contains? @seen scope-key)
                  acc
                  (do
                    (vswap! seen conj scope-key)
                    (conj acc dataset-scope)))))
            []
            dataset-scopes)))

(defn available-skill-graph-ids
  "Return the currently registered skill graph IDs as durable strings.
   Assumes the skill registry has already been initialized by the caller."
  []
  (->> (skills-api/list-skill-graphs)
       (map :id)
       (map normalize-skill-graph-id)
       set))

(defn agent-id-for-skill-graph
  "Resolve a builtin/default agent ID for a skill graph when one is known."
  [skill-graph-id]
  (let [skill-graph-id (normalize-skill-graph-id skill-graph-id)]
    (or (some (fn [agent]
                (when (= skill-graph-id (:default-skill-graph agent))
                  (:id agent)))
              (builtin-agent-definitions))
        (some (fn [agent]
                (when (some #{skill-graph-id} (:allowed-skill-graphs agent))
                  (:id agent)))
              (builtin-agent-definitions)))))

(defn normalize-agent
  "Normalize an agent definition to the canonical application shape."
  [agent]
  (let [default-skill-graph (normalize-skill-graph-id
                              (or (:default-skill-graph agent)
                                  (:agent/default-skill-graph agent)))
        allowed-skill-graphs (->> (or (:allowed-skill-graphs agent)
                                      (:agent/allowed-skill-graphs agent)
                                      [])
                                  (map normalize-skill-graph-id)
                                  (remove str/blank?)
                                  distinct
                                  vec)
        normalized-skill-graphs (if (seq allowed-skill-graphs)
                                  allowed-skill-graphs
                                  (cond-> [] default-skill-graph (conj default-skill-graph)))
        normalized-dataset-scopes (->> (or (:allowed-dataset-scopes agent)
                                           (:agent/allowed-dataset-scopes agent)
                                         [])
                                     (map normalize-dataset-scope)
                                     distinct-dataset-scopes
                                     vec)
        guardrails (or (:guardrails agent)
                       (:agent/guardrails agent)
                       {})
        skill-params (or (:skill-params agent)
                         (:agent/skill-params agent)
                         {})]
    {:id (or (:id agent) (:agent/id agent))
     :name (or (:name agent) (:agent/name agent))
     :description (or (:description agent) (:agent/description agent))
     :instructions (or (:instructions agent) (:agent/instructions agent))
     :default-skill-graph default-skill-graph
     :allowed-skill-graphs (if (and default-skill-graph
                                    (not (some #(= % default-skill-graph) normalized-skill-graphs)))
                             (conj normalized-skill-graphs default-skill-graph)
                             normalized-skill-graphs)
     :allowed-dataset-scopes normalized-dataset-scopes
     :guardrails guardrails
     :skill-params skill-params
     :enabled? (if (contains? agent :enabled?)
                 (:enabled? agent)
                 (if (contains? agent :agent/enabled?)
                   (:agent/enabled? agent)
                   true))
     :created-at (or (:created-at agent) (:agent/created-at agent))
     :updated-at (or (:updated-at agent) (:agent/updated-at agent))}))

(defn validate-agent
  "Validate an agent definition and return a structured result.

   Optional opts:
     :available-skill-graphs   Set of skill-graph IDs (strings) that exist in
                               the in-memory registry. Defaults to the current
                               registry contents.
     :dataset-scope-checker    Fn `(fn [{:keys [tenant dataset-config-key]}] -> truthy?)`
                               that returns truthy iff the scope resolves to a
                               real dataset. When supplied, scopes that don't
                               resolve produce a validation error pointing at
                               the bad keys. Stay pure here — callers that have
                               a config-DB connection plumb the checker through
                               (see `digdir.agents.db/upsert-agent!`)."
  ([agent]
   (validate-agent agent {}))
  ([agent {:keys [available-skill-graphs dataset-scope-checker]}]
   (let [agent (normalize-agent agent)
         available-graphs (or available-skill-graphs
                              (available-skill-graph-ids))
         unresolved-scopes (when dataset-scope-checker
                             (->> (:allowed-dataset-scopes agent)
                                  (remove (fn [{:keys [tenant dataset-config-key] :as s}]
                                            (or (str/blank? tenant)
                                                (str/blank? dataset-config-key)
                                                (boolean (dataset-scope-checker s)))))
                                  vec))
         errors (cond-> []
                  (str/blank? (:id agent))
                  (conj "Agent :id is required.")

                  (str/blank? (:name agent))
                  (conj "Agent :name is required.")

                  (str/blank? (:description agent))
                  (conj "Agent :description is required.")

                  (str/blank? (:instructions agent))
                  (conj "Agent :instructions is required.")

                  (str/blank? (:default-skill-graph agent))
                  (conj "Agent :default-skill-graph is required.")

                  (not (boolean? (:enabled? agent)))
                  (conj "Agent :enabled? must be a boolean.")

                  (not (map? (:guardrails agent)))
                  (conj "Agent :guardrails must be a map.")

                  (not (map? (:skill-params agent)))
                  (conj "Agent :skill-params must be a map.")

                  ;; Each entry in :skill-params must be {<skill-id-kw> <param-map>}.
                  ;; The inner map's contents are validated by the individual
                  ;; skills at execution time — we only enforce the outer shape
                  ;; here so a malformed payload trips at upsert, not mid-call.
                  (and (map? (:skill-params agent))
                       (some (fn [[k v]]
                               (or (not (keyword? k))
                                   (not (map? v))))
                             (:skill-params agent)))
                  (conj (str "Agent :skill-params must be a map of "
                             "{<skill-id-keyword> <param-map>}; got entries "
                             (->> (:skill-params agent)
                                  (remove (fn [[k v]] (and (keyword? k) (map? v))))
                                  (map (fn [[k _v]] (pr-str k)))
                                  sort
                                  vec)))

                  (and (:default-skill-graph agent)
                       (not (contains? available-graphs (:default-skill-graph agent))))
                  (conj (str "Unknown default skill graph: " (:default-skill-graph agent)))

                  (some #(not (contains? available-graphs %)) (:allowed-skill-graphs agent))
                  (conj (str "Unknown allowed skill graphs: "
                             (->> (:allowed-skill-graphs agent)
                                  (remove #(contains? available-graphs %))
                                  sort
                                  vec)))

                  (and (:default-skill-graph agent)
                       (not (some #(= % (:default-skill-graph agent))
                                  (:allowed-skill-graphs agent))))
                  (conj "Agent :default-skill-graph must be included in :allowed-skill-graphs.")

                  (some (fn [{:keys [tenant dataset-config-key]}]
                          (or (str/blank? tenant)
                              (str/blank? dataset-config-key)))
                        (:allowed-dataset-scopes agent))
                  (conj "Every :allowed-dataset-scope must include non-blank :tenant and :dataset-config-key.")

                  (seq unresolved-scopes)
                  (conj (str "Allowed dataset scopes do not resolve to a known dataset: "
                             (->> unresolved-scopes
                                  (map (fn [{:keys [tenant dataset-config-key]}]
                                         (str tenant "/" dataset-config-key)))
                                  sort
                                  vec)
                             ". Use the bare :dataset/id (e.g. \"public-docs\"), "
                             "not a node-id path fragment (e.g. \"digdir/public-docs\").")))]
     {:valid? (empty? errors)
      :errors errors
      :agent agent})))

(defn validate-agent!
  "Validate an agent definition and throw on failure."
  ([agent]
   (validate-agent! agent {}))
  ([agent opts]
   (let [{:keys [valid? errors agent]} (validate-agent agent opts)]
     (when-not valid?
       (throw (ex-info "Invalid agent definition"
                       {:errors errors
                        :agent agent})))
     agent)))

(def ^:private production-agent-definitions
  "Agents that ship in every build. Every skill graph named here must be
   registered from src/ — see digdir.skills.init/verify-skill-graphs, which
   digdir.skills.init-test asserts.

   NOTE on `:guardrails` (2026-08-24, #240): guardrails are stored, exported
   and surfaced through `digdir.agents.policy/resolve-execution-policy`, but
   nothing in the runtime reads them to change behaviour. They are declared
   intent, not enforcement. Where an agent's behaviour actually is guaranteed,
   it is guaranteed by the shape of the graph it names — see
   `builtin/retrieve-only-agent` below."
  [{:id "builtin/fact-checker-agent"
    :name "Fact Checker Agent"
    :description "Verification-focused agent for checking claims against evidence."
    :instructions "Verify claims against retrieved evidence and distinguish confidence clearly."
    :default-skill-graph "builtin/fact-checker"
    :allowed-skill-graphs ["builtin/fact-checker"]
    :allowed-dataset-scopes []
    :guardrails {:answer-style :verification}
    :enabled? true}

   {:id "builtin/agent-rag-agent"
    :name "Agentic RAG Agent"
    :description "General-purpose agentic retrieval assistant."
    :instructions "Use the available retrieval and reasoning tools to answer grounded questions."
    :default-skill-graph "builtin/agent-rag-graph-bundled"
    :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"
                           "builtin/agent-rag-graph-faithful"]
    :allowed-dataset-scopes []
    :guardrails {:citations-required true
                 :tool-use :allowed}
    :enabled? true}

   ;; ==========================================================================
   ;; #240 — the three agents #81 dropped, redefined rather than restored.
   ;;
   ;; #81 regenerated the config snapshot from what the code defines, which is
   ;; why these vanished: they named :builtin/research-assistant,
   ;; :builtin/retrieve-only and :builtin/simple-qa — three of the four graphs
   ;; retired in Phase 0 (:builtin/agent-rag was the fourth). Restoring the old
   ;; definitions verbatim would put agents back in the list pointing at dead
   ;; graphs — which reads as success, because the agent appears, and fails at
   ;; the first invocation. So each one below states what it is FOR, and names
   ;; a graph that is registered from src/ today.
   ;; ==========================================================================

   ;; FOR: breadth-first retrieval and a synthesis-heavy answer, for questions
   ;; where the reader wants the survey rather than the snippet.
   ;;
   ;; Names builtin/agent-rag-graph-faithful, which exists. The retired
   ;; :builtin/research-assistant graph was plan -> retrieve -> rerank ->
   ;; generate — byte-for-byte the same steps as :builtin/simple-qa, so
   ;; "research" was never a property of the graph. The multi-pass search that
   ;; actually earns the name is what the agent-rag loop does. The faithful
   ;; variant is the default because its per-step decomposition is what makes a
   ;; research answer auditable; bundled is allowed for when speed matters more.
   ;;
   ;; The differentiation from builtin/agent-rag-agent is therefore in
   ;; skill-params, not in a separate graph: a wider retrieval pool and more
   ;; context carried into synthesis. Claiming it any other way would be
   ;; claiming a graph difference that does not exist.
   {:id "builtin/research-assistant-agent"
    :name "Research Assistant Agent"
    :description "Breadth-first retrieval with a synthesis-heavy answer, for questions that want a survey of the sources rather than a single fact."
    :instructions "Search broadly before answering. Prefer covering the range of what the sources say, including disagreement between them, over answering from the first sufficient passage. Ground every claim in a citation."
    :default-skill-graph "builtin/agent-rag-graph-faithful"
    :allowed-skill-graphs ["builtin/agent-rag-graph-faithful"
                           "builtin/agent-rag-graph-bundled"]
    :allowed-dataset-scopes []
    :guardrails {:citations-required true
                 :answer-style :research}
    :skill-params {:builtin/retrieval {:retrieve-top-k 150}
                   :builtin/rerank {:top-k 60
                                    :context-top-k 20}}
    :enabled? true}

   ;; FOR: diagnostics — hand back the evidence the retrieval stack would have
   ;; given a synthesiser, and stop, so retrieval quality can be inspected
   ;; without an LLM in the loop to mask or invent.
   ;;
   ;; Names builtin/retrieve-only, which is registered from
   ;; digdir.skills.templates.builtin. That graph is NOT the retired one
   ;; restored: the retired version predates the user-intent first pass and
   ;; corpus-aware expansion, so it would have measured a retrieval path
   ;; production no longer runs.
   ;;
   ;; "Stops before synthesis" is guaranteed by the graph having no generation
   ;; step, not by the :allow-final-answer guardrail — see the note on
   ;; guardrails above. The guardrail records the intent; the graph enforces it.
   {:id "builtin/retrieve-only-agent"
    :name "Retrieve Only Agent"
    :description "Diagnostic agent that returns retrieved and reranked evidence without generating an answer."
    :instructions "Return the retrieved and reranked evidence for inspection. Do not generate an answer."
    :default-skill-graph "builtin/retrieve-only"
    :allowed-skill-graphs ["builtin/retrieve-only"]
    :allowed-dataset-scopes []
    :guardrails {:allow-final-answer false}
    :enabled? true}

   ;; FOR: a short synthesised answer with inline citations, shown ahead of the
   ;; underlying results rather than instead of them — the Google AI Overviews
   ;; shape. This REPLACES :builtin/simple-qa rather than restoring it; the PI
   ;; decision in #240 was explicit that the third agent is new product work.
   ;;
   ;; Names builtin/ai-overview, registered from digdir.skills.builtin.overview.
   ;; That namespace's docstring is the written definition of what this agent
   ;; does when retrieval is thin, which #240 requires: it declines, before the
   ;; LLM call, and says why. Read it before changing any default here.
   ;;
   ;; Deliberately no :builtin/synthesis skill-params: the overview runs its
   ;; synthesis under its own skill id so a dataset-level
   ;; skills.synthesis.generation-prompt cannot silently turn the overview back
   ;; into an ordinary long answer.
   {:id "builtin/ai-overview-agent"
    :name "AI Overview Agent"
    :description "Short, fully-cited overview answer presented ahead of the underlying results. Declines rather than answering when retrieval is thin."
    :instructions "Answer in at most three sentences, drawn only from the retrieved sources, with a citation on every sentence. If the sources do not support a direct answer, decline instead of guessing."
    :default-skill-graph "builtin/ai-overview"
    :allowed-skill-graphs ["builtin/ai-overview"]
    :allowed-dataset-scopes []
    :guardrails {:citations-required true
                 :answer-style :overview}
    :enabled? true}

   ;; Round-5 sweep winner shipped as an opt-in agent. The phrase-only
   ;; weights + strategy contribution caps are tuned for the altinn-docs
   ;; corpus and shouldn't be applied as defaults — they zero out the
   ;; semantic (content) and metadata strategies, which hurts recall on
   ;; queries whose phrasing diverges from the corpus's exact phrasing.
   ;; The other knobs (retrieve-top-k=100, rerank top-k=20) match or are
   ;; more conservative than the existing hardcoded defaults; they're
   ;; included here to mirror the sweep matrix exactly rather than for
   ;; their own contribution.
   ;;
   ;; NOTE (2026-05-28): the Round-5 winner was tuned against the
   ;; pre-slice-23 / pre-slice-25 retrieval stack. Slice 23 user-intent
   ;; union + slice 25 doc-title pass-2 ranking have since shipped on
   ;; establish-baseline; phrase-only weights now actively suppress the
   ;; metadata/content signal that those slices depend on. Treat the
   ;; preset as a historical artifact and re-tune against the current
   ;; v3-baseline before recommending it for production traffic.
   {:id "digdir/altinn-docs-tuned"
    :name "Altinn Docs Tuned Agent"
    :description "Round-5-tuned retrieval for the altinn-docs corpus. Phrase-only weights with strategy caps; opt-in alternative to the general-purpose agent."
    :instructions "Use the available retrieval and reasoning tools to answer grounded questions about Altinn documentation."
    :default-skill-graph "builtin/agent-rag-graph-faithful"
    :allowed-skill-graphs ["builtin/agent-rag-graph-faithful"
                           "builtin/agent-rag-graph-bundled"]
    :allowed-dataset-scopes []
    :guardrails {:citations-required true
                 :tool-use :allowed}
    :skill-params {:builtin/retrieval {:strategy-weights {:content 0.0
                                                          :phrase 1.0
                                                          :metadata 0.0}
                                       :strategy-contribution-caps {:phrase 5
                                                                    :content 0
                                                                    :metadata 0}
                                       :retrieve-top-k 100}
                   :builtin/rerank {:top-k 20}}
    :enabled? true}

   ;; Slice 1 of #82 (#89). Present in production but OPT-IN: enabling by
   ;; default waits for slice 3's cost controls, because a user who turns it
   ;; on can still trigger a job of roughly 12 agent runs per chunk with no
   ;; quota. That should be a deliberate act.
   ;;
   ;; Only graphs that exist in src/ are named here, so the #71 resolvability
   ;; gate stays green on a production classpath. digdir.agents.dev widens the
   ;; allowed list with the src-dev-only self-improve graphs when it loads.
   {:id "builtin/docs-agent"
    :name "Docs Agent"
    :description "Public-docs curation agent. Hosts the docs/* skill graphs: release-note cross-check, translation drift, outline drafting, and per-chunk hypothetical-questions enrichment. Enrichment writes to a parallel collection keyed by chunk_id and never mutates base collections."
    :instructions "Pick the docs/* skill graph that matches the task: outline drafting, release-notes-to-page mapping, NB/EN translation drift, or enriching a chunk with hypothetical questions."
    :default-skill-graph "docs/enrich-one-chunk"
    :allowed-skill-graphs ["docs/enrich-one-chunk"
                           "docs/outline-graph"
                           "docs/release-cross-check"
                           "docs/release-cross-check-v2"
                           "docs/translation-drift"]
    ;; Unrestricted, like every other builtin. It previously named
    ;; {:tenant "digdir" :dataset-config-key "public-docs"} — a tenant the
    ;; product no longer ships, so the declaration was a dangling reference.
    ;;
    ;; Widening is safe because #470 made the API KEY'S GRANT THE FLOOR: an
    ;; agent declaration can only narrow what a key may reach, never widen it.
    ;; Measured before changing it, with an enabled agent and a key granting a
    ;; different tenant: the stale scope failed LOUDLY either way —
    ;; `no_dataset_scope` with no arguments, `dataset_not_authorized` when the
    ;; removed tenant was named explicitly — so this was never a silent grant.
    ;; It is removed because a dangling name is wrong, not because it leaked.
    :allowed-dataset-scopes []
    :guardrails {:answer-style :research
                 :citations-required true}
    :enabled? false}])

(defn- load-dev-agent-definitions
  "Agent definitions from `digdir.agents.dev`, or `[]` when src-dev is not on
   the classpath (i.e. a production build).

   Only a *missing namespace* is tolerated. Any other failure — a typo, a
   broken require, a graph that will not register — propagates, because
   swallowing those is precisely how issue #71 stayed invisible.

   The dynamic require is deliberate: it is a src -> src-dev reference, kept
   out of the ns form both because a static require would not compile in a
   production build and because digdir.build.src-dev-boundary-test (#30)
   correctly forbids one there. Same pattern as
   digdir.skills.init/initialize! and digdir.config.db's agent seeding."
  []
  (try
    (require 'digdir.agents.dev)
    {:definitions (if-let [v (resolve 'digdir.agents.dev/dev-agent-definitions)]
                    (vec @v)
                    [])
     :graph-additions (if-let [v (resolve 'digdir.agents.dev/dev-agent-graph-additions)]
                        @v
                        {})}
    (catch java.io.FileNotFoundException _
      ;; No src-dev on the classpath — expected in production builds.
      {:definitions [] :graph-additions {}})))

(defn- widen-allowed-skill-graphs
  "Merge dev-only graphs into an existing agent's :allowed-skill-graphs.

   Widening only — it never introduces an agent and never changes a default.
   That keeps one definition per agent (issue #89 asks for the dev-gated
   definition to be updated, not duplicated) while letting a dev build offer
   graphs a production build has no code for."
  [agents additions]
  (mapv (fn [agent]
          (if-let [extra (get additions (:id agent))]
            (update agent :allowed-skill-graphs
                    #(vec (distinct (into (vec %) extra))))
            agent))
        agents))

(def ^:private !dev-agent-definitions
  "Deferred on purpose. Loading `digdir.agents.dev` pulls in the graphs it
   registers — a heavy chain through the enrichment namespaces — and that
   chain has reached back into this namespace before. The observed cycle was

     agents.dev -> demo.self-improve-graph -> skills.enrichment.eval-delta ->
     tools.diagnostics -> api.context -> agents.db -> agents.core

   which fails outright at load. Slice 2a (#94) removed the `eval-delta` hop,
   so that exact path is gone; the deferral stays because the chain is still
   wide enough that another route back is easy to reintroduce, and because
   every caller reads the definitions at runtime anyway, by which point a
   cycle is moot. Turning this into an eager `def` would be a load-order
   experiment, not a simplification."
  (delay (load-dev-agent-definitions)))

(defn builtin-agent-definitions
  "Production agents, plus any src-dev-only agents when those are loadable.

   A function, not a var, so the src-dev load stays off this namespace's own
   load path (see `!dev-agent-definitions`).

   Development tooling that depends on src-dev-only skill graphs belongs in
   `digdir.agents.dev`, which registers those graphs as it loads — so an agent
   is never present without the graphs it names (issue #71)."
  []
  (let [{:keys [definitions graph-additions]} @!dev-agent-definitions]
    (-> (into production-agent-definitions definitions)
        (widen-allowed-skill-graphs graph-additions))))
