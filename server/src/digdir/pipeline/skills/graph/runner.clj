(ns digdir.pipeline.skills.graph.runner
  "Skill graph execution engine.

   Executes skill graphs by:
   1. Validating the graph
   2. Topologically sorting steps
   3. Executing steps with input resolution
   4. Collecting outputs"
  (:require [digdir.pipeline.skills.graph.schema :as schema]
            [digdir.rag.skills.core :as skills]
            [digdir.pipeline.skills.context :as ctx]
            [missionary.core :as m]
            [clojure.string :as str]))

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
     ref - Input reference (:$var, :step-id, or [:step-id :key])
     graph-inputs - Map of graph input values
     step-outputs - Map of step-id -> output map

   Returns: Resolved value"
  [ref graph-inputs step-outputs]
  (cond
    ;; Graph input reference
    (and (keyword? ref) (schema/graph-input? ref))
    (let [input-name (keyword (subs (name ref) 1))]
      (get graph-inputs input-name))

    ;; Step output with specific key
    (vector? ref)
    (let [[step-id output-key] ref]
      (get-in step-outputs [step-id :outputs output-key]))

    ;; Step output (entire outputs map)
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

(defn execute-step
  "Execute a single graph step.

   Args:
     step - Graph step definition
     resolved-inputs - Map of resolved input values
     execution-opts - Map with :tenant, :environment, :pipeline-config

   Returns: Skill execution result"
  [step resolved-inputs execution-opts]
  (let [skill-id (:skill step)
        parameters (merge
                     (:parameters step)
                     (select-keys execution-opts [:model :temperature]))
        ctx (ctx/build-execution-context
              skill-id
              resolved-inputs
              (assoc execution-opts :parameters parameters))]
    (skills/execute-skill skill-id ctx)))

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
       :environment - Environment
       :pipeline-config - Pipeline configuration

   Returns: Map with :outputs (collected outputs) and :step-results (all step results)"
  [graph inputs opts]
  ;; Validate graph
  (schema/fully-validate-graph! graph)

  (let [execution-order (topological-sort graph)
        step-map (into {} (map (juxt :id identity) (:steps graph)))]

    ;; Execute steps in order
    (loop [remaining execution-order
           step-outputs {}]
      (if (empty? remaining)
        ;; Collect final outputs
        (let [output-keys (:outputs graph)
              final-outputs (reduce
                              (fn [acc step-id]
                                (let [result (get step-outputs step-id)]
                                  (if (skills/result-success? result)
                                    (merge acc (skills/get-result-outputs result))
                                    acc)))
                              {}
                              (keys step-outputs))]
          {:outputs final-outputs
           :step-results step-outputs})

        ;; Execute next step
        (let [step-id (first remaining)
              step (get step-map step-id)]
          (if-not (should-execute-step? step inputs step-outputs)
            ;; Skip step
            (recur (rest remaining)
                   (assoc step-outputs step-id
                          (skills/success-result {} {:skipped true})))

            ;; Execute step
            (let [resolved-inputs (resolve-step-inputs step inputs step-outputs)
                  result (execute-step step resolved-inputs opts)]
              (if (and (skills/result-error? result)
                       (not= :skip (:on-error step)))
                ;; Error and not configured to skip
                (if (= :default (:on-error step))
                  ;; Continue with default/empty outputs
                  (recur (rest remaining)
                         (assoc step-outputs step-id
                                (skills/success-result {} {:defaulted true :error result})))
                  ;; Fail
                  (throw (ex-info "Step execution failed"
                                  {:step-id step-id
                                   :error result})))
                ;; Success or skip-on-error
                (recur (rest remaining)
                       (assoc step-outputs step-id result))))))))))

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

(defn run-template
  "Execute a graph template with inputs.

   Args:
     template - Template map with :graph key
     inputs - Map of input values
     opts - Execution options

   Returns: Graph execution result"
  [template inputs opts]
  (run-graph (:graph template) inputs opts))

(defn get-graph-output
  "Get a specific output from graph results.

   Args:
     result - Graph execution result
     output-key - Key of output to retrieve

   Returns: Output value or nil"
  [result output-key]
  (get-in result [:outputs output-key]))
