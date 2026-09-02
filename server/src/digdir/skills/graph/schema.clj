(ns digdir.skills.graph.schema
  "Malli schemas for skill graph validation.

   A skill graph defines a workflow of skill executions where:
   - Each step invokes a skill with mapped inputs
   - Inputs can come from graph inputs (:$var) or previous step outputs (:step-id)
   - Steps form a DAG (directed acyclic graph)"
  (:require [malli.core :as m]
            [malli.error :as me]))

;; =============================================================================
;; Input Reference Schema
;; =============================================================================

;; Input references can be:
;; - :$user-query (graph input, prefixed with $)
;; - :retrieve (output from step with id :retrieve)
;; - [:retrieve :chunks] (specific output key from step)

(def InputRef
  "Schema for input references in graph steps.
   Can be a keyword (graph input if starts with $, or step id)
   or a vector [step-id output-key] for specific outputs,
   or a single-element vector [:$ref] to wrap a scalar into an array,
   or any other value as a literal (e.g., [] for empty list)."
  [:or
   keyword?
   [:vector keyword?]
   any?])

;; =============================================================================
;; Graph Step Schema
;; =============================================================================

(def RegularStep
  "Schema for a single skill-invocation step in a skill graph."
  [:map
   {:doc "A single skill execution step in the graph"}
   [:id keyword?]
   [:skill keyword?]
   [:inputs [:map-of keyword? InputRef]]
   [:parameters {:optional true} [:map-of keyword? any?]]
   [:condition {:optional true} [:or keyword? vector? fn?]]
   [:on-error {:optional true} [:enum :skip :fail :default]]])

(def ForeachStep
  "Schema for a foreach step: invokes a single inner skill OR a sub-graph
   once per element of a referenced collection. Inner step is the same
   shape as RegularStep minus the :id (which is borne by the outer
   foreach step). Iteration scope exposes :$item (the element) and
   optionally :$idx (the position) inside `:do`'s inputs.

   Sub-graph inner steps were added 2026-05-19. Before that, a foreach
   over a sub-graph required a thin `:builtin/run-sub-graph` dispatcher
   skill. The shape now matches LoopInnerStep — same expressiveness in
   both bounded-iteration primitives."
  [:map
   {:doc "A fan-out step that runs an inner skill or sub-graph once per element of `:over`."}
   [:id keyword?]
   [:foreach
    [:map
     [:over InputRef]
     [:as {:optional true} keyword?]
     [:as-index {:optional true} keyword?]]]
   [:do
    [:or
     [:map
      [:skill keyword?]
      [:inputs [:map-of keyword? InputRef]]
      [:parameters {:optional true} [:map-of keyword? any?]]]
     [:map
      [:sub-graph
       [:map
        [:graph-id keyword?]
        [:inputs {:optional true} [:map-of keyword? InputRef]]]]]]]
   [:collect-as keyword?]
   [:condition {:optional true} [:or keyword? vector? fn?]]
   [:on-error {:optional true} [:enum :skip :fail :default]]])

(def SubGraphStep
  "Schema for a sub-graph step: invokes a registered child skill-graph as a
   single step. The child graph runs to completion; its declared :outputs map
   becomes this step's outputs. Useful when a multi-step composition should be
   addressable as one unit (e.g. an agent iteration with N internal steps).

   :graph-id resolves at execution time against the skill-graph registry —
   validation does NOT check that the referenced graph is registered, so
   sub-graph steps remain shippable independently of their referents."
  [:map
   {:doc "A step that runs a registered child graph."}
   [:id keyword?]
   [:sub-graph
    [:map
     [:graph-id keyword?]
     [:inputs {:optional true} [:map-of keyword? InputRef]]]]
   [:condition {:optional true} [:or keyword? vector? fn?]]
   [:on-error {:optional true} [:enum :skip :fail :default]]])

;; The inner step of a loop can be either a single skill invocation
;; (`{:skill :inputs :parameters?}`) or a sub-graph invocation
;; (`{:sub-graph {:graph-id :inputs?}}`). Same expressiveness as a top-level
;; step minus its :id (which is borne by the outer loop step).
(def LoopInnerStep
  [:or
   [:map
    [:skill keyword?]
    [:inputs [:map-of keyword? InputRef]]
    [:parameters {:optional true} [:map-of keyword? any?]]]
   [:map
    [:sub-graph
     [:map
      [:graph-id keyword?]
      [:inputs {:optional true} [:map-of keyword? InputRef]]]]]])

(def LoopStep
  "Schema for a loop step: repeats an inner step until either a break condition
   is met or :max-iterations is reached.

   Break condition:
     :until-output K  — stop AFTER the iteration whose outputs contain a
                        truthy value at key K (post-test, default).
     :while-output K  — stop BEFORE running an iteration when the previous
                        iteration's K is falsy (pre-test). Exclusive with
                        :until-output; if both are set the post-test wins.

   Iteration scope (each inner-step :inputs ref):
     :$<iteration-as>       — the previous iteration's outputs map (defaults
                              to :$iter; the first iteration sees an empty
                              map).
     :$<iteration-index-as> — the current iteration index 0..N-1 (defaults
                              to :$i).

   Output:
     :outputs has one key, named by `:collect-as`, holding the vector of
     per-iteration outputs maps. The step's metadata records :exhausted?,
     :iteration-count, and per-iteration timings."
  [:map
   {:doc "A bounded-iteration step that runs an inner skill or sub-graph."}
   [:id keyword?]
   [:loop
    [:map
     [:max-iterations [:int {:min 1}]]
     [:until-output {:optional true} keyword?]
     [:while-output {:optional true} keyword?]
     [:iteration-as {:optional true} keyword?]
     [:iteration-index-as {:optional true} keyword?]]]
   [:do LoopInnerStep]
   [:collect-as keyword?]
   [:condition {:optional true} [:or keyword? vector? fn?]]
   [:on-error {:optional true} [:enum :skip :fail :default]]])

(def SelectStep
  "Schema for a select step: chooses one of several inner steps based on a
   dispatch value, like a cond expression. The dispatch value is read at
   runtime from `:select/:on` (an input ref). The matching branch's `:do` is
   executed; if no branch matches, `:default` runs.

   Branch keys may be any comparable value (keyword, string, number); the
   runner compares with =. Branch :default is required."
  [:map
   {:doc "A step that runs one of several inner steps based on a runtime value."}
   [:id keyword?]
   [:select [:map [:on InputRef]]]
   [:branches [:map-of any? [:map [:do LoopInnerStep]]]]
   [:condition {:optional true} [:or keyword? vector? fn?]]
   [:on-error {:optional true} [:enum :skip :fail :default]]])

(def DispatchByNameStep
  "Schema for a dispatch-by-name step: resolves a name + registry at runtime,
   looks up the name in the registry, and runs the matched skill with the
   resolved `:inputs` map.

   Use case: an LLM-emitted tool call (where the LLM picks the tool name at
   runtime) dispatches to one of N registered skills. Conceptually a
   `multimethod-as-a-step` primitive — generalizes the `:builtin/agent-tool-call`
   wrapper from Phase 2.1.

   `:registry` resolves to a map of name -> skill-id (keyword). An
   unrecognised name produces a :dispatch-by-name/unknown-name error.

   `:inputs` follows the same shape as regular-step :inputs (map of keyword
   to InputRef). The dispatched skill receives these as its :inputs map."
  [:map
   {:doc "A step that dispatches to one of N skills by a runtime name."}
   [:id keyword?]
   [:dispatch-by-name
    [:map
     [:name InputRef]
     [:registry InputRef]
     [:inputs {:optional true} [:map-of keyword? InputRef]]]]
   [:condition {:optional true} [:or keyword? vector? fn?]]
   [:on-error {:optional true} [:enum :skip :fail :default]]])

(def GraphStep
  "A graph step is one of: regular skill invocation, foreach fan-out, sub-graph
   invocation, bounded loop, select-branch dispatch, or runtime-named dispatch.
   Discriminator: presence of :foreach / :sub-graph / :loop / :select /
   :dispatch-by-name; absence of all means regular."
  [:multi {:dispatch (fn [step]
                       (cond
                         (contains? step :loop)             :loop
                         (contains? step :foreach)          :foreach
                         (contains? step :sub-graph)        :sub-graph
                         (contains? step :select)           :select
                         (contains? step :dispatch-by-name) :dispatch-by-name
                         :else                              :regular))}
   [:regular RegularStep]
   [:foreach ForeachStep]
   [:sub-graph SubGraphStep]
   [:loop LoopStep]
   [:select SelectStep]
   [:dispatch-by-name DispatchByNameStep]])

(defn foreach-step?
  "True if `step` is a foreach fan-out step."
  [step]
  (and (map? step) (contains? step :foreach)))

(defn sub-graph-step?
  "True if `step` is a sub-graph invocation step."
  [step]
  ;; NB: a :loop step whose inner :do is a sub-graph will also contain
  ;; :sub-graph in :do, but NOT at the top level. This check is top-level only.
  (and (map? step) (contains? step :sub-graph) (not (contains? step :loop))))

(defn loop-step?
  "True if `step` is a bounded loop step."
  [step]
  (and (map? step) (contains? step :loop)))

(defn select-step?
  "True if `step` is a select-branch dispatch step."
  [step]
  (and (map? step) (contains? step :select)))

(defn dispatch-by-name-step?
  "True if `step` is a dispatch-by-name (runtime-resolved skill) step."
  [step]
  (and (map? step) (contains? step :dispatch-by-name)))

(defn- loop-inner-sub-graph?
  "True if a loop step's `:do` block is a sub-graph invocation rather than a
   single skill."
  [loop-step]
  (and (loop-step? loop-step)
       (contains? (:do loop-step) :sub-graph)))

;; =============================================================================
;; Graph Definition Schema
;; =============================================================================

(def SkillGraph
  "Schema for a complete skill graph definition."
  [:map
   {:doc "Complete skill graph workflow definition"}
   [:id {:optional true} keyword?]
   [:name {:optional true} string?]
   [:description {:optional true} string?]
   [:inputs [:vector keyword?]]
   [:outputs [:vector keyword?]]
   [:steps [:vector GraphStep]]
   [:entry-point {:optional true} keyword?]
   [:version {:optional true} string?]])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn valid-graph?
  "Check if a graph definition is valid according to schema.

   Args:
     graph - Graph definition map

   Returns: true if valid"
  [graph]
  (m/validate SkillGraph graph))

(defn explain-graph
  "Explain validation errors for a graph.

   Args:
     graph - Graph definition map

   Returns: Human-readable error map or nil if valid"
  [graph]
  (when-let [explanation (m/explain SkillGraph graph)]
    (me/humanize explanation)))

(defn validate-graph!
  "Validate a graph definition, throw if invalid.

   Args:
     graph - Graph definition map

   Throws: ex-info with validation errors
   Returns: graph if valid"
  [graph]
  (if (valid-graph? graph)
    graph
    (throw (ex-info "Invalid skill graph"
                    {:graph graph
                     :errors (explain-graph graph)}))))

;; =============================================================================
;; Semantic Validation
;; =============================================================================

(defn extract-step-ids
  "Extract all step IDs from a graph."
  [graph]
  (set (map :id (:steps graph))))

(defn- iteration-scope-ref?
  "True if `ref` resolves inside foreach OR loop iteration scope, OR
   resolves against a loop step's own :inputs map. These are NOT
   validated as graph inputs or prior-step refs.

   - Foreach: :$<as> (default :$item) and :$<as-index> (default :$idx).
   - Loop iteration scope: :$<iteration-as> (default :$iter) and
     :$<iteration-index-as> (default :$i).
   - Loop step inputs scope: :$<k> for each key `k` in the loop step's
     own :inputs map. These resolve at runtime via the runner's
     `(merge inputs iter-scope)` so the inner sub-graph can reference
     them as graph-input-style refs without declaring them as outer
     graph inputs."
  [step ref]
  (when (keyword? ref)
    (cond
      (foreach-step? step)
      (let [as-key  (or (-> step :foreach :as) :item)
            idx-key (or (-> step :foreach :as-index) :idx)
            iteration-vars #{(keyword (str "$" (name as-key)))
                             (keyword (str "$" (name idx-key)))}]
        (contains? iteration-vars ref))

      (loop-step? step)
      (let [iter-key (or (-> step :loop :iteration-as) :iter)
            idx-key  (or (-> step :loop :iteration-index-as) :i)
            iter-scope-vars #{(keyword (str "$" (name iter-key)))
                              (keyword (str "$" (name idx-key)))}
            input-scope-vars (->> (keys (or (:inputs step) {}))
                                  (map #(keyword (str "$" (name %))))
                                  set)
            iteration-vars (into iter-scope-vars input-scope-vars)]
        (contains? iteration-vars ref))

      :else false)))

(defn- input-map->refs
  "Project an :inputs map into its set of upstream-step / graph-input refs.
   Filters out iteration-scope vars when called against a foreach step."
  [step inputs-map]
  (->> inputs-map
       vals
       (keep (fn [ref]
               (let [head (cond
                            (vector? ref) (first ref)
                            (keyword? ref) ref
                            :else nil)]
                 (when (and head (not (iteration-scope-ref? step head)))
                   head))))
       set))

(defn extract-input-refs
  "Extract all upstream input references from a step.

   - Regular steps: refs from `:inputs`.
   - Foreach steps: refs from `:foreach/:over` plus `:do/:inputs` (with
     iteration-scope vars like :$item / :$idx filtered out).
   - Sub-graph steps: refs from `:sub-graph/:inputs`.
   - Loop steps: refs from `:do/:inputs` (or `:do/:sub-graph/:inputs` when
     the loop body is a sub-graph). Iteration-scope vars like :$iter / :$i
     are filtered."
  [step]
  (cond
    (foreach-step? step)
    (let [over-ref (-> step :foreach :over)
          over-head (cond
                      (vector? over-ref) (first over-ref)
                      (keyword? over-ref) over-ref
                      :else nil)
          ;; Foreach :do can be either a single-skill map (`:skill :inputs`)
          ;; or a sub-graph invocation (`:sub-graph {:graph-id :inputs}`).
          ;; The latter shape was added 2026-05-19 to match loop's expressiveness.
          inner-inputs (if (contains? (:do step) :sub-graph)
                         (-> step :do :sub-graph :inputs)
                         (-> step :do :inputs))
          inner-refs (input-map->refs step inner-inputs)]
      (cond-> inner-refs
        over-head (conj over-head)))

    (sub-graph-step? step)
    (input-map->refs step (-> step :sub-graph :inputs))

    (loop-step? step)
    (let [inner-inputs (if (loop-inner-sub-graph? step)
                         (-> step :do :sub-graph :inputs)
                         (-> step :do :inputs))
          ;; Step's own :inputs map establishes dependencies on prior
          ;; steps / outer graph inputs the runner uses to thread state
          ;; into iter-inputs. Without these the topological sort can
          ;; place a loop step before the steps it actually reads.
          outer-refs (input-map->refs step (:inputs step))]
      (into outer-refs (input-map->refs step inner-inputs)))

    (select-step? step)
    (let [on-ref (-> step :select :on)
          on-head (cond
                    (vector? on-ref) (first on-ref)
                    (keyword? on-ref) on-ref
                    :else nil)
          branches (-> step :branches vals)
          branch-refs (reduce (fn [acc branch]
                                (let [d (:do branch)
                                      inputs (if (contains? d :sub-graph)
                                               (-> d :sub-graph :inputs)
                                               (:inputs d))]
                                  (into acc (input-map->refs step inputs))))
                              #{}
                              branches)]
      (cond-> branch-refs
        on-head (conj on-head)))

    (dispatch-by-name-step? step)
    (let [{:keys [name registry inputs]} (:dispatch-by-name step)
          head-of (fn [ref]
                    (cond
                      (vector? ref) (first ref)
                      (keyword? ref) ref
                      :else nil))
          single-refs (->> [name registry]
                           (keep head-of)
                           (remove nil?))
          input-refs (input-map->refs step inputs)]
      (into (set single-refs) input-refs))

    :else
    (input-map->refs step (:inputs step))))

(defn graph-input?
  "Check if a keyword is a graph input reference (starts with $)."
  [k]
  (and (keyword? k)
       (.startsWith (name k) "$")))

(defn validate-input-refs
  "Validate that all input references are valid.

   Returns nil if valid, error map if invalid."
  [graph]
  (let [step-ids (extract-step-ids graph)
        graph-inputs (set (:inputs graph))
        steps (:steps graph)]
    (reduce
      (fn [errors step]
        (let [step-id (:id step)
              step-idx (.indexOf steps step)
              prior-step-ids (set (map :id (take step-idx steps)))
              refs (extract-input-refs step)]
          (reduce
            (fn [errs ref]
              (cond
                ;; Graph input - check if declared
                (graph-input? ref)
                (let [input-name (keyword (subs (name ref) 1))]
                  (if (contains? graph-inputs input-name)
                    errs
                    (conj errs {:step step-id
                                :ref ref
                                :error :undeclared-graph-input
                                :available graph-inputs})))

                ;; Step reference - check if step exists and comes before
                (contains? step-ids ref)
                (if (contains? prior-step-ids ref)
                  errs
                  (conj errs {:step step-id
                              :ref ref
                              :error :forward-reference
                              :message "Step references a later step"}))

                :else
                (conj errs {:step step-id
                            :ref ref
                            :error :unknown-reference})))
            errors
            refs)))
      []
      steps)))

(defn detect-cycles
  "Detect cycles in the graph using DFS.

   Returns: Vector of cycle paths if cycles found, nil otherwise."
  [graph]
  ;; Build adjacency list
  (let [steps (:steps graph)
        _step-map (into {} (map (juxt :id identity) steps))
        adjacencies (into {}
                          (map (fn [step]
                                 [(:id step)
                                  (->> (extract-input-refs step)
                                       (remove graph-input?)
                                       vec)])
                               steps))]
    ;; DFS for cycle detection
    (loop [to-visit (keys adjacencies)
           visited #{}
           path []
           cycles []]
      (if (empty? to-visit)
        (when (seq cycles) cycles)
        (let [node (first to-visit)]
          (if (contains? visited node)
            (recur (rest to-visit) visited path cycles)
            (let [deps (get adjacencies node [])
                  cycle-deps (filter #(some #{%} path) deps)]
              (if (seq cycle-deps)
                (recur (rest to-visit)
                       (conj visited node)
                       path
                       (conj cycles {:node node :cycle-to cycle-deps}))
                (recur (rest to-visit)
                       (conj visited node)
                       path
                       cycles)))))))))

(defn validate-graph-semantics
  "Perform semantic validation on a graph.

   Checks:
   - All input references are valid
   - No forward references (step can only ref prior steps)
   - No cycles

   Returns: nil if valid, error map if invalid"
  [graph]
  (let [ref-errors (validate-input-refs graph)
        cycles (detect-cycles graph)]
    (when (or (seq ref-errors) cycles)
      {:input-ref-errors ref-errors
       :cycles cycles})))

(defn fully-validate-graph!
  "Perform complete validation (schema + semantics).

   Throws: ex-info if invalid
   Returns: graph if valid"
  [graph]
  (validate-graph! graph)
  (when-let [semantic-errors (validate-graph-semantics graph)]
    (throw (ex-info "Skill graph has semantic errors"
                    {:graph graph
                     :errors semantic-errors})))
  graph)
