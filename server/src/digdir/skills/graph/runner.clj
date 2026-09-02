(ns digdir.skills.graph.runner
  "Skill graph execution engine.

   Executes skill graphs by:
   1. Validating the graph
   2. Topologically sorting steps
   3. Executing steps with input resolution
   4. Collecting outputs"
  (:require [digdir.skills.graph.schema :as schema]
            [digdir.skills.graph.trace :as graph-trace]
            [digdir.skills.templates.core :as templates]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.context :as ctx]
            [digdir.skills.events :as events]
            [missionary.core :as m]))

;; =============================================================================
;; Topological Sort
;; =============================================================================

(defn build-dependency-graph
  "Build a dependency graph from step input references."
  [graph]
  (let [steps (:steps graph)]
    (into {}
          (map (fn [step]
                 [(:id step)
                  (->> (schema/extract-input-refs step)
                       (remove schema/graph-input?)
                       vec)])
               steps))))

(defn topological-sort
  "Topologically sort graph steps.

   Returns: Vector of step IDs in execution order"
  [graph]
  (let [deps (build-dependency-graph graph)
        steps (:steps graph)
        step-ids (map :id steps)]
    ;; Kahn's algorithm
    (loop [result []
           remaining (set step-ids)
           in-degree (into {} (map (fn [id]
                                     [id (count (get deps id []))])
                                   step-ids))]
      (if (empty? remaining)
        result
        (let [;; Find nodes with no incoming edges (all deps satisfied)
              ready (->> remaining
                         (filter #(zero? (get in-degree %)))
                         first)]
          (if ready
            (let [;; Update in-degrees for nodes that depend on this one
                  new-in-degree (reduce
                                  (fn [deg id]
                                    (if (some #{ready} (get deps id []))
                                      (update deg id dec)
                                      deg))
                                  in-degree
                                  remaining)]
              (recur (conj result ready)
                     (disj remaining ready)
                     new-in-degree))
            ;; No ready nodes means cycle (shouldn't happen after validation)
            (throw (ex-info "Cycle detected during topological sort"
                            {:remaining remaining
                             :in-degree in-degree}))))))))

;; =============================================================================
;; Input Resolution
;; =============================================================================

(defn resolve-input-ref
  "Resolve a single input reference.

   Args:
     ref - Input reference. Supported shapes:
       :$var                - Whole graph-input value
       [:$var :k]           - Get key :k out of graph-input :var
       [:$var :k1 :k2 ...]  - n-element path into a graph-input map
       :step-id             - Whole outputs map of a prior step
       [:step-id :k]        - Single output key from a prior step
       [:step-id :k1 :k2]   - n-element path into a prior step's nested output
     graph-inputs - Map of graph input values
     step-outputs - Map of step-id -> output map

   Returns: Resolved value, or nil if any path segment is missing.

   n-element vector refs were added 2026-05-19 so callers can reach
   into nested output structures (e.g. `[:eval :summary :gate-pass]`)
   without forcing the producer skill to mirror the field at the
   outputs root."
  [ref graph-inputs step-outputs]
  (cond
    ;; Whole graph input reference
    (and (keyword? ref) (schema/graph-input? ref))
    (let [input-name (keyword (subs (name ref) 1))]
      (get graph-inputs input-name))

    ;; Vector form — `[:$var :k1 :k2 ...]` (graph-input nested path)
    ;; or `[:step-id :k1 :k2 ...]` (prior-step nested path). Empty or
    ;; single-element vecs are tolerated — only the head matters for
    ;; the dispatch.
    (vector? ref)
    (let [[head & path] ref]
      (if (and (keyword? head) (schema/graph-input? head))
        (let [input-name (keyword (subs (name head) 1))]
          (get-in graph-inputs (cons input-name path)))
        (get-in step-outputs (concat [head :outputs] path))))

    ;; Whole step-outputs map
    (keyword? ref)
    (get-in step-outputs [ref :outputs])

    :else
    ref))

(defn resolve-step-inputs
  "Resolve all inputs for a step.

   Args:
     step - Graph step definition
     graph-inputs - Map of graph input values
     step-outputs - Map of step-id -> result

   Returns: Map of resolved input values"
  [step graph-inputs step-outputs]
  (reduce-kv
    (fn [acc input-name ref]
      (assoc acc input-name (resolve-input-ref ref graph-inputs step-outputs)))
    {}
    (:inputs step)))

;; =============================================================================
;; Step Execution
;; =============================================================================

(defn resolve-step-parameters
  "Resolve parameters for a step from skill graph defaults, skill params, and execution overrides."
  [step execution-opts]
  (let [skill-id (:skill step)
        skill-params (:skill-params execution-opts)
        common-params (select-keys skill-params [:model :temperature :max-tokens :prompt])
        per-skill-params (get skill-params skill-id)
        execution-overrides (select-keys execution-opts [:model :temperature :max-tokens :prompt])]
    (merge
      (:parameters step)
      common-params
      per-skill-params
      execution-overrides)))

(defn- skill-id->stage
  [skill-id]
  (some-> skill-id name keyword))

(defn- effective-skill-id
  "Returns the skill id that this step ultimately invokes. Regular steps:
   :skill. Foreach steps: :do/:skill. Sub-graph steps: :sub-graph/:graph-id.
   Loop steps: :do/:skill or :do/:sub-graph/:graph-id. Select and
   dispatch-by-name steps don't have a single inner skill (the dispatch is
   resolved at runtime), so they report the step's :id as a placeholder."
  [step]
  (cond
    (schema/loop-step? step)
    (or (-> step :do :skill)
        (-> step :do :sub-graph :graph-id))

    (schema/foreach-step? step)          (-> step :do :skill)
    (schema/sub-graph-step? step)        (-> step :sub-graph :graph-id)
    (schema/select-step? step)           (:id step)
    (schema/dispatch-by-name-step? step) (:id step)
    :else                                (:skill step)))

(defn- build-step-timing
  [step-id step duration-ms status]
  (let [skill-id (effective-skill-id step)]
    (cond-> {:step-id step-id
             :skill-id skill-id
             :stage (skill-id->stage skill-id)
             :duration-ms duration-ms
             :status status}
      (= status :skipped) (assoc :skipped true)
      (= status :defaulted) (assoc :defaulted true)
      (schema/foreach-step? step) (assoc :foreach? true)
      (schema/sub-graph-step? step) (assoc :sub-graph? true)
      (schema/loop-step? step) (assoc :loop? true)
      (schema/select-step? step) (assoc :select? true)
      (schema/dispatch-by-name-step? step) (assoc :dispatch-by-name? true))))

(defn execute-step
  "Execute a single graph step.

   Args:
     step - Graph step definition
     resolved-inputs - Map of resolved input values
     execution-opts - Map with :tenant, :dataset-config-key, optional :runtime-config-key, and :skill-params
   Returns: Skill execution result"
  [step resolved-inputs execution-opts]
  (let [skill-id (:skill step)
        parameters (resolve-step-parameters step execution-opts)
        ctx (ctx/build-execution-context
              skill-id
              resolved-inputs
              (assoc execution-opts :parameters parameters))]
    (skills/execute-skill skill-id ctx)))

(defn- foreach-iteration-scope
  "Build the iteration scope map for a foreach step at index `idx`. Keys are
   the user-configured names (:as / :as-index, defaulting to :item / :idx) so
   that resolving :$item / :$idx via the standard graph-input path returns the
   per-iteration value."
  [step item idx]
  (let [as-key (or (-> step :foreach :as) :item)
        idx-key (or (-> step :foreach :as-index) :idx)]
    {as-key item
     idx-key idx}))

;; Forward declare: execute-foreach-step (defined below) can route into
;; execute-sub-graph-step when its `:do` block is a sub-graph. Loop step
;; uses the same indirection (`(declare run-graph)` above its own
;; forward refs).
(declare execute-sub-graph-step)

(defn- inner-step-for-execution
  "Materialize a foreach step's `:do` block as either a regular-shaped
   step or a sub-graph step (both carrying the outer foreach's :id).
   Routing in `execute-foreach-step` branches on whether the result map
   contains `:sub-graph`. Mirrors `inner-step-for-loop`."
  [foreach-step]
  (let [d (:do foreach-step)]
    (if (contains? d :sub-graph)
      {:id (:id foreach-step)
       :sub-graph (:sub-graph d)}
      {:id (:id foreach-step)
       :skill (:skill d)
       :inputs (:inputs d)
       :parameters (:parameters d)})))

(defn execute-foreach-step
  "Execute a foreach step: invoke the inner skill OR sub-graph once per
   element of the resolved :over collection.

   Returns a skills/success-result whose :outputs has one key, named by the
   step's :collect-as, holding a vector of per-iteration outputs maps.

   Per-iteration failures honor the outer step's :on-error setting:
   - :fail (default) - aborts on first failure, returning a skills/error-result
   - :skip - drops failed iterations from the result vector
   - :default - inserts an empty outputs map in the failed iteration's slot

   The metadata reports per-iteration timings and status under :iterations.

   Sub-graph inner steps (added 2026-05-19) dispatch through
   `execute-sub-graph-step`; the inputs are resolved against
   `iter-inputs` first, then passed into the sub-graph runner as its
   graph-inputs map."
  [foreach-step over-collection inputs step-outputs execution-opts]
  (let [{:keys [collect-as on-error]} foreach-step
        on-error (or on-error :fail)
        inner (inner-step-for-execution foreach-step)
        sub-graph-inner? (contains? inner :sub-graph)
        items (vec (or over-collection []))]
    (loop [idx 0
           remaining items
           collected []
           iter-metas []]
      (if (empty? remaining)
        (skills/success-result
         {collect-as collected}
         {:iterations iter-metas
          :iteration-count (count iter-metas)
          :collected-count (count collected)})
        (let [item (first remaining)
              iter-scope (foreach-iteration-scope foreach-step item idx)
              iter-inputs (merge inputs iter-scope)
              ;; Inner step's :inputs are resolved against iter-inputs
              ;; (outer inputs merged with per-iteration scope).
              inner-inputs-map (if sub-graph-inner?
                                 (-> inner :sub-graph :inputs)
                                 (:inputs inner))
              resolved (reduce-kv
                        (fn [acc k ref]
                          (assoc acc k (resolve-input-ref ref iter-inputs step-outputs)))
                        {}
                        inner-inputs-map)
              iter-start (System/currentTimeMillis)
              result (if sub-graph-inner?
                       (execute-sub-graph-step inner resolved execution-opts)
                       (execute-step inner resolved execution-opts))
              iter-duration (- (System/currentTimeMillis) iter-start)
              base-meta {:idx idx
                         :duration-ms iter-duration
                         :status (if (skills/result-success? result) :ok :error)}]
          (cond
            ;; Success - collect this iteration's outputs map
            (skills/result-success? result)
            (recur (inc idx)
                   (rest remaining)
                   (conj collected (skills/get-result-outputs result))
                   (conj iter-metas base-meta))

            ;; Error + :fail - abort whole foreach
            (= on-error :fail)
            (skills/error-result
             :foreach/iteration-failed
             (str "Foreach step " (:id foreach-step)
                  " failed at iteration " idx)
             {:step-id (:id foreach-step)
              :idx idx
              :inner-error result
              :iterations (conj iter-metas (assoc base-meta :error result))})

            ;; Error + :skip - drop this iteration, keep going
            (= on-error :skip)
            (recur (inc idx)
                   (rest remaining)
                   collected
                   (conj iter-metas (assoc base-meta :skipped? true :error result)))

            ;; Error + :default - empty outputs in this slot
            :else
            (recur (inc idx)
                   (rest remaining)
                   (conj collected {})
                   (conj iter-metas (assoc base-meta :defaulted? true :error result)))))))))

;; Forward declaration so execute-sub-graph-step can call back into run-graph
;; recursively. Defined below.
(declare run-graph)

(defn- loop-iteration-scope
  "Build the iteration scope map for a loop step. Keys are the user-configured
   iteration-as / iteration-index-as (defaults :iter / :i). The previous
   iteration's outputs (or {} for the first) is exposed under iteration-as,
   the current iteration index under iteration-index-as. Standard
   graph-input resolution via :$name then picks them up inside the inner
   step's :inputs map."
  [loop-step prev-outputs idx]
  (let [iter-key (or (-> loop-step :loop :iteration-as) :iter)
        idx-key  (or (-> loop-step :loop :iteration-index-as) :i)]
    {iter-key (or prev-outputs {})
     idx-key idx}))

(defn- inner-step-for-loop
  "Materialize a loop step's `:do` block as either a regular step (with the
   outer :id) or a sub-graph step (with the outer :id). Routing in the loop
   body branches on this discriminator."
  [loop-step]
  (let [d (:do loop-step)]
    (if (contains? d :sub-graph)
      {:id (:id loop-step)
       :sub-graph (:sub-graph d)}
      {:id (:id loop-step)
       :skill (:skill d)
       :inputs (:inputs d)
       :parameters (:parameters d)})))

(declare execute-loop-step)

(def ^:private propagated-execution-keys
  "Execution-context keys that are auto-forwarded as graph-inputs to a
   child sub-graph (in addition to whatever the parent passed in
   :inputs). These are the keys that effectively form an ambient
   execution context — they apply to every skill that touches the
   tenant config DB or runs a dataset-scoped operation.

   Without this propagation, a sub-graph step that references
   `:$tenant` etc. would resolve to nil whenever the parent didn't
   spell them out in its `:inputs` map (because the playground passes
   tenant via execution-opts/skill-params, not as a graph input)."
  [:tenant :dataset-config-key :tenant-config-key :runtime-config-key :agent-id])

(defn execute-sub-graph-step
  "Execute a sub-graph step: resolve the referenced child graph from the
   templates registry, run it with the step's resolved inputs, return the
   child's :outputs map as a skills/success-result. The child trace is
   accessible via run-graph's normal trace-file emission and via this
   step's metadata (:child-graph-id, :child-step-results).

   Auto-forwards `propagated-execution-keys` (tenant, the config-key
   trio, agent-id) from execution-opts into the child's graph-inputs.
   Caller-supplied keys in `resolved-inputs` win on collision. This
   matches the convention loop/foreach already had via the dispatcher
   skill — keeping it inside the runner means sub-graphs can reliably
   reference `:$tenant` without each parent needing to spell it out."
  [sub-graph-step resolved-inputs execution-opts]
  (let [{:keys [graph-id]} (:sub-graph sub-graph-step)
        skill-graph (templates/get-skill-graph graph-id)
        child-graph (some-> skill-graph :graph)
        forwarded (->> propagated-execution-keys
                       (keep (fn [k] (when-some [v (get execution-opts k)]
                                       [k v])))
                       (into {}))
        enriched-inputs (merge forwarded resolved-inputs)]
    (if-not child-graph
      (skills/error-result
       :sub-graph/graph-not-found
       (str "Sub-graph " graph-id " is not registered in the skill-graph registry.")
       {:step-id (:id sub-graph-step)
        :graph-id graph-id
        :available-graph-ids (templates/list-skill-graph-ids)})
      (try
        (let [child-result (run-graph child-graph enriched-inputs execution-opts)]
          (skills/success-result
           (:outputs child-result)
           {:child-graph-id graph-id
            :child-step-count (count (get-in child-result [:execution-metadata :step-timings]))
            :child-duration-ms (get-in child-result [:execution-metadata :total-duration-ms])}))
        (catch clojure.lang.ExceptionInfo e
          (skills/error-result
           :sub-graph/child-execution-failed
           (str "Sub-graph " graph-id " execution failed: " (.getMessage e))
           {:step-id (:id sub-graph-step)
            :graph-id graph-id
            :child-error (ex-data e)}))))))

(defn execute-loop-step
  "Execute a loop step: run the inner step in a bounded loop, threading the
   previous iteration's outputs as :$iter into the next iteration. Break
   conditions:
   - `:until-output K`: stop AFTER the iteration whose outputs has truthy K
     (post-test, default semantics).
   - `:while-output K`: stop BEFORE running an iteration when the previous
     iteration's K is falsy (pre-test; first iteration always runs).
   - Either condition unmet by the time :max-iterations is reached
     → `:exhausted? true` is recorded in metadata.

   Inner step can be a single skill OR a sub-graph (discriminated by the
   presence of :sub-graph in `:do`). Per-iteration outputs are collected as
   a vector under `:collect-as`."
  [loop-step inputs step-outputs execution-opts]
  (let [{:keys [max-iterations until-output while-output]} (:loop loop-step)
        {:keys [collect-as on-error]} loop-step
        on-error (or on-error :fail)
        inner (inner-step-for-loop loop-step)
        sub-graph-inner? (contains? inner :sub-graph)
        ;; Resolve the loop step's own :inputs map against the outer graph
        ;; inputs + step-outputs. These become available to the inner step
        ;; via the same `:$name` reference syntax as graph-inputs (they
        ;; merge into iter-inputs below, before iter-scope).
        loop-step-inputs (when-some [imap (:inputs loop-step)]
                           (reduce-kv
                            (fn [acc k ref]
                              (assoc acc k (resolve-input-ref ref inputs step-outputs)))
                            {}
                            imap))
        merged-inputs (merge inputs loop-step-inputs)]
    (loop [idx 0
           prev-outputs nil
           collected []
           iter-metas []]
      (cond
        ;; Pre-test (while-output) — break BEFORE next iteration when
        ;; previous iteration's :while-output key is falsy. First iteration
        ;; always runs (prev-outputs is nil so the check is skipped).
        (and while-output
             (some? prev-outputs)
             (not (get prev-outputs while-output)))
        (skills/success-result
         {collect-as collected}
         {:iterations iter-metas
          :iteration-count (count iter-metas)
          :exhausted? false
          :break-reason :while-output-falsy})

        ;; Hard cap reached without break
        (>= idx max-iterations)
        (skills/success-result
         {collect-as collected}
         {:iterations iter-metas
          :iteration-count (count iter-metas)
          :exhausted? true
          :break-reason :max-iterations-reached})

        :else
        (let [iter-scope (loop-iteration-scope loop-step prev-outputs idx)
              iter-inputs (merge merged-inputs iter-scope)
              iter-start (System/currentTimeMillis)
              ;; Inner step's :inputs are resolved against (inputs ∪ scope).
              inner-inputs-map (if sub-graph-inner?
                                 (-> inner :sub-graph :inputs)
                                 (:inputs inner))
              resolved (reduce-kv
                        (fn [acc k ref]
                          (assoc acc k (resolve-input-ref ref iter-inputs step-outputs)))
                        {}
                        inner-inputs-map)
              ;; Sub-graphs invoked from inside a loop don't need their own
              ;; per-iteration trace files — the outer graph's trace already
              ;; captures everything via :step-results / :iterations. Writing
              ;; per-iteration trace files adds multi-MB pprint + I/O on the
              ;; hot path. Suppress for sub-graph inner; regular skill inner
              ;; never writes its own trace either way.
              iter-opts (if sub-graph-inner?
                          (assoc execution-opts ::suppress-graph-trace? true)
                          execution-opts)
              result (if sub-graph-inner?
                       (execute-sub-graph-step inner resolved iter-opts)
                       (execute-step inner resolved iter-opts))
              iter-duration (- (System/currentTimeMillis) iter-start)
              base-meta {:idx idx :duration-ms iter-duration
                         :status (if (skills/result-success? result) :ok :error)}
              iter-outputs (when (skills/result-success? result)
                             (skills/get-result-outputs result))]
          (cond
            ;; Success — collect outputs and check post-test break
            (skills/result-success? result)
            (let [collected' (conj collected iter-outputs)
                  iter-metas' (conj iter-metas
                                    (cond-> base-meta
                                      until-output
                                      (assoc :until-output-value
                                             (get iter-outputs until-output))))
                  until-met? (and until-output (get iter-outputs until-output))]
              (if until-met?
                (skills/success-result
                 {collect-as collected'}
                 {:iterations iter-metas'
                  :iteration-count (count iter-metas')
                  :exhausted? false
                  :break-reason :until-output-truthy})
                (recur (inc idx) iter-outputs collected' iter-metas')))

            ;; Error + :fail — abort the whole loop
            (= on-error :fail)
            (skills/error-result
             :loop/iteration-failed
             (str "Loop step " (:id loop-step) " failed at iteration " idx)
             {:step-id (:id loop-step)
              :idx idx
              :inner-error result
              :iterations (conj iter-metas (assoc base-meta :error result))})

            ;; Error + :skip — drop iteration, continue
            (= on-error :skip)
            (recur (inc idx) prev-outputs collected
                   (conj iter-metas (assoc base-meta :skipped? true :error result)))

            ;; Error + :default — empty outputs in slot, continue
            :else
            (recur (inc idx) {} (conj collected {})
                   (conj iter-metas (assoc base-meta :defaulted? true :error result)))))))))

(defn execute-select-step
  "Execute a select-branch step. The :on input ref is resolved, the matching
   branch (or :default) is selected, and its :do block runs. Inner :do is
   either a regular skill or a sub-graph (same shape as loop's :do)."
  [select-step inputs step-outputs execution-opts]
  (let [on-ref (-> select-step :select :on)
        dispatch-value (resolve-input-ref on-ref inputs step-outputs)
        branches (:branches select-step)
        branch (or (get branches dispatch-value)
                   (get branches :default))]
    (if-not branch
      (skills/error-result
       :select/no-matching-branch-and-no-default
       (str "Select step " (:id select-step)
            " has no matching branch for dispatch value " (pr-str dispatch-value)
            " and no :default branch.")
       {:step-id (:id select-step)
        :dispatch-value dispatch-value
        :branch-keys (vec (keys branches))})
      (let [inner (:do branch)
            sub-graph-inner? (contains? inner :sub-graph)
            inner-step (cond-> {:id (:id select-step)}
                         sub-graph-inner?
                         (assoc :sub-graph (:sub-graph inner))

                         (not sub-graph-inner?)
                         (assoc :skill (:skill inner)
                                :inputs (:inputs inner)
                                :parameters (:parameters inner)))
            inner-inputs-map (if sub-graph-inner?
                               (-> inner :sub-graph :inputs)
                               (:inputs inner))
            resolved (reduce-kv
                      (fn [acc k ref]
                        (assoc acc k (resolve-input-ref ref inputs step-outputs)))
                      {}
                      inner-inputs-map)
            result (if sub-graph-inner?
                     (execute-sub-graph-step inner-step resolved execution-opts)
                     (execute-step inner-step resolved execution-opts))]
        (if (skills/result-success? result)
          (skills/success-result
           (skills/get-result-outputs result)
           (assoc (or (skills/get-result-metadata result) {})
                  :selected-branch (if (get branches dispatch-value)
                                     dispatch-value
                                     :default)
                  :dispatch-value dispatch-value))
          result)))))

(defn execute-dispatch-by-name-step
  "Execute a dispatch-by-name step: resolve :name and :registry at runtime,
   look up :name in :registry to find the skill-id, run that skill with its
   resolved :inputs map.

   `:registry` resolves to a map of name -> skill-id (keyword). An
   unrecognized :name produces a :dispatch-by-name/unknown-name error.

   Metadata records :resolved-skill-id, :resolved-name, and :registry-keys
   so the trace shows which skill the dispatch landed on."
  [step graph-inputs step-outputs execution-opts]
  (let [{:keys [name registry inputs]} (:dispatch-by-name step)
        resolved-name (resolve-input-ref name graph-inputs step-outputs)
        resolved-registry (resolve-input-ref registry graph-inputs step-outputs)
        resolved-inputs (reduce-kv
                         (fn [acc k ref]
                           (assoc acc k (resolve-input-ref ref graph-inputs step-outputs)))
                         {}
                         (or inputs {}))
        skill-id (get resolved-registry resolved-name)]
    (cond
      (nil? resolved-registry)
      (skills/error-result
       :dispatch-by-name/registry-not-resolved
       (str "dispatch-by-name step " (:id step) " could not resolve :registry")
       {:step-id (:id step) :name-value resolved-name})

      (not skill-id)
      (skills/error-result
       :dispatch-by-name/unknown-name
       (str "dispatch-by-name step " (:id step) ": no skill registered for name "
            (pr-str resolved-name))
       {:step-id (:id step)
        :name-value resolved-name
        :registry-keys (vec (keys resolved-registry))})

      :else
      (let [inner-step {:id (:id step)
                        :skill skill-id
                        :inputs resolved-inputs
                        :parameters {}}
            result (execute-step inner-step resolved-inputs execution-opts)]
        (if (skills/result-success? result)
          (skills/success-result
           (skills/get-result-outputs result)
           (assoc (or (skills/get-result-metadata result) {})
                  :resolved-skill-id skill-id
                  :resolved-name resolved-name
                  :registry-keys (vec (keys resolved-registry))))
          result)))))

(defn should-execute-step?
  "Check if a step should execute based on its condition."
  [step graph-inputs step-outputs]
  (if-let [condition (:condition step)]
    (cond
      (fn? condition)
      (condition graph-inputs step-outputs)

      (keyword? condition)
      (let [ref-value (resolve-input-ref condition graph-inputs step-outputs)]
        (boolean ref-value))

      ;; #348. A vector condition is an input-ref, resolved exactly as
      ;; `:inputs` refs are: `[:step-id :k]`, `[:$var :k]`, n-element paths.
      ;; Before this, the only way to condition on ONE output key of a prior
      ;; step was an inline `fn` — a bare keyword resolves to that step's whole
      ;; outputs MAP, which is truthy whenever the step ran at all. So the fn
      ;; was not a stylistic divergence; it was the only expressible form, and
      ;; it put a function object in the registry, which is unserializable
      ;; across the Electric boundary and blanked the Skill Graphs admin screen.
      (vector? condition)
      (boolean (resolve-input-ref condition graph-inputs step-outputs))

      :else true)
    true))

;; =============================================================================
;; Graph Execution
;; =============================================================================

(defn run-graph
  "Execute a skill graph synchronously.

   Args:
     graph - Validated graph definition
     inputs - Map of input values (keys without $ prefix)
     opts - Execution options:
       :tenant - Tenant identifier
       :dataset-config-key - dataset selector
       :runtime-config-key - runtime selector for graph/template execution
       :skill-params - Skill parameter overrides by skill-id
   Returns: Map with :outputs, :step-results, and :execution-metadata (timing)"
  [graph inputs opts]
  ;; Validate graph
  (schema/fully-validate-graph! graph)

  (let [execution-order (topological-sort graph)
        step-map (into {} (map (juxt :id identity) (:steps graph)))
        graph-start (System/currentTimeMillis)
        progress-fn (:progress-fn opts)
        collect-outputs (fn [step-outputs]
                          (reduce
                           (fn [acc step-id]
                             (let [result (get step-outputs step-id)]
                               (if (skills/result-success? result)
                                 (merge acc (skills/get-result-outputs result))
                                 acc)))
                           {}
                           (keys step-outputs)))
        suppress-trace? (::suppress-graph-trace? opts)
        emit-trace! (fn [status step-outputs step-timings duration-ms error]
                      (when-not suppress-trace?
                        (graph-trace/write-trace-file!
                         {:graph-id (:id graph)
                          :inputs inputs
                          :opts opts
                          :run-status status
                          :duration-ms duration-ms
                          :step-defs (:steps graph)
                          :step-timings step-timings
                          :step-results step-outputs
                          :outputs (collect-outputs step-outputs)
                          :error error})))]

    ;; Execute steps in order
    (loop [remaining execution-order
           step-outputs {}
           step-timings {}
           stage-timings []]
      (if (empty? remaining)
        ;; Collect final outputs
        (let [graph-end (System/currentTimeMillis)
              total-duration (- graph-end graph-start)
              final-outputs (collect-outputs step-outputs)]
          (events/emit-progress! progress-fn
                                 (events/graph-completed (count execution-order)
                                                         total-duration))
          (emit-trace! :ok step-outputs step-timings total-duration nil)
          {:outputs final-outputs
           :step-results step-outputs
           :execution-metadata {:total-duration-ms total-duration
                                :steps-executed (count execution-order)
                                :step-timings step-timings
                                :stage-timings stage-timings}})

        ;; Execute next step
        (let [step-id (first remaining)
              step (get step-map step-id)]
          (if-not (should-execute-step? step inputs step-outputs)
            ;; Skip step
            (do
              (events/emit-progress! progress-fn
                                     (events/step-skipped step-id (effective-skill-id step)))
              (recur (rest remaining)
                     (assoc step-outputs step-id
                            (skills/success-result {} {:skipped true}))
                     (assoc step-timings step-id (build-step-timing step-id step 0 :skipped))
                     (conj stage-timings (build-step-timing step-id step 0 :skipped))))

            ;; Execute step (regular, foreach, sub-graph, loop, select, or dispatch-by-name)
            (let [foreach? (schema/foreach-step? step)
                  sub-graph? (schema/sub-graph-step? step)
                  loop? (schema/loop-step? step)
                  select? (schema/select-step? step)
                  dispatch? (schema/dispatch-by-name-step? step)
                  inner-skill-id (effective-skill-id step)
                  step-start (System/currentTimeMillis)
                  _ (events/emit-progress! progress-fn
                                           (events/step-started step-id inner-skill-id))
                  resolved-inputs (cond
                                    foreach? nil
                                    loop?    nil
                                    select?  nil
                                    dispatch? nil
                                    sub-graph? (let [inputs-decl (-> step :sub-graph :inputs)]
                                                 (reduce-kv
                                                  (fn [acc k ref]
                                                    (assoc acc k (resolve-input-ref ref inputs step-outputs)))
                                                  {}
                                                  inputs-decl))
                                    :else (resolve-step-inputs step inputs step-outputs))
                  result (cond
                           foreach?
                           (let [over-ref (-> step :foreach :over)
                                 over-coll (resolve-input-ref over-ref inputs step-outputs)]
                             (execute-foreach-step step over-coll inputs step-outputs opts))

                           sub-graph?
                           (execute-sub-graph-step step resolved-inputs opts)

                           loop?
                           (execute-loop-step step inputs step-outputs opts)

                           select?
                           (execute-select-step step inputs step-outputs opts)

                           dispatch?
                           (execute-dispatch-by-name-step step inputs step-outputs opts)

                           :else
                           (execute-step step resolved-inputs opts))
                  step-end (System/currentTimeMillis)
                  step-duration (- step-end step-start)]
              (if (and (skills/result-error? result)
                       (not= :skip (:on-error step)))
                ;; Error and not configured to skip
                (if (= :default (:on-error step))
                       ;; Continue with default/empty outputs
                       (do
                         (events/emit-progress! progress-fn
                                                (events/step-defaulted step-id
                                                                       inner-skill-id
                                                                       step-duration))
                         (recur (rest remaining)
                                (assoc step-outputs step-id
                                       (skills/success-result {} {:defaulted true :error result}))
                                (assoc step-timings step-id (build-step-timing step-id step step-duration :defaulted))
                                (conj stage-timings (build-step-timing step-id step step-duration :defaulted))))
                  ;; Fail
                  (let [step-outputs-with-failure (assoc step-outputs step-id result)
                        step-timings-with-failure (assoc step-timings step-id
                                                         (build-step-timing step-id step step-duration :error))]
                    (events/emit-progress! progress-fn
                                           (events/step-failed step-id
                                                               inner-skill-id
                                                               step-duration
                                                               result))
                    (emit-trace! :error
                                 step-outputs-with-failure
                                 step-timings-with-failure
                                 (- (System/currentTimeMillis) graph-start)
                                 {:step-id step-id :error result})
                    (throw (ex-info "Step execution failed"
                                    {:step-id step-id
                                     :error result}))))
                ;; Success or skip-on-error
                (do
                  (events/emit-progress! progress-fn
                                         (events/step-completed step-id
                                                                inner-skill-id
                                                                step-duration
                                                                ;; Per-step outputs so the playground's
                                                                ;; live observability can render rich
                                                                ;; per-skill detail for graph variants
                                                                ;; (otherwise it has only :duration-ms
                                                                ;; to work with).
                                                                (skills/get-result-outputs result)))
                  (recur (rest remaining)
                         (assoc step-outputs step-id result)
                         (assoc step-timings step-id (build-step-timing step-id step step-duration :ok))
                         (conj stage-timings (build-step-timing step-id step step-duration :ok))))))))))))

(defn run-graph-async
  "Execute a skill graph asynchronously using Missionary.

   Args:
     graph - Validated graph definition
     inputs - Map of input values
     opts - Execution options

   Returns: Missionary task that resolves to graph result"
  [graph inputs opts]
  (m/sp
    (run-graph graph inputs opts)))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn run-skill-graph
  "Execute a skill graph definition with inputs.

   Args:
     skill-graph - Skill graph map with :graph key
     inputs - Map of input values
     opts - Execution options

   Returns: Graph execution result"
  [skill-graph inputs opts]
  (run-graph (:graph skill-graph) inputs opts))

(defn get-graph-output
  "Get a specific output from graph results.

   Args:
     result - Graph execution result
     output-key - Key of output to retrieve

   Returns: Output value or nil"
  [result output-key]
  (get-in result [:outputs output-key]))
