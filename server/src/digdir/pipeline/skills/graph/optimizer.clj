(ns digdir.pipeline.skills.graph.optimizer
  "Graph optimization utilities for skill execution.

   Provides optimizations for skill graphs:
   1. Parallel execution levels - identify steps that can run concurrently
   2. Execution plan generation - pre-compute optimal execution strategy
   3. Output dependency analysis - identify unused outputs for pruning
   4. Critical path analysis - identify the longest execution path

   These optimizations help runners execute graphs more efficiently,
   particularly for IO-bound operations like API calls."
  (:require [digdir.pipeline.skills.graph.schema :as schema]
            [clojure.set :as set]))

;; =============================================================================
;; Dependency Analysis
;; =============================================================================

(defn build-dependency-map
  "Build a map of step-id -> set of step dependencies.

   Args:
     graph - Graph definition

   Returns: Map of step-id -> #{dependent-step-ids}"
  [graph]
  (let [steps (:steps graph)]
    (into {}
          (map (fn [step]
                 [(:id step)
                  (->> (schema/extract-input-refs step)
                       (remove schema/graph-input?)
                       set)])
               steps))))

(defn build-reverse-dependency-map
  "Build a map of step-id -> set of steps that depend on it.

   Args:
     graph - Graph definition

   Returns: Map of step-id -> #{steps-that-need-this-step}"
  [graph]
  (let [deps (build-dependency-map graph)
        step-ids (set (map :id (:steps graph)))]
    (reduce
      (fn [acc [step-id step-deps]]
        (reduce
          (fn [inner-acc dep]
            (update inner-acc dep (fnil conj #{}) step-id))
          acc
          step-deps))
      (into {} (map (fn [id] [id #{}]) step-ids))
      deps)))

(defn get-step-depth
  "Calculate the depth (longest path from root) for each step.

   Args:
     graph - Graph definition

   Returns: Map of step-id -> depth (0 = no dependencies)"
  [graph]
  (let [deps (build-dependency-map graph)
        step-ids (map :id (:steps graph))]
    (loop [depths {}
           remaining (set step-ids)]
      (if (empty? remaining)
        depths
        (let [;; Find steps whose dependencies are all resolved
              ready (filter
                      (fn [id]
                        (every? #(contains? depths %) (get deps id)))
                      remaining)]
          (if (empty? ready)
            ;; Should not happen with valid DAG
            (throw (ex-info "Unable to resolve depths - possible cycle"
                            {:remaining remaining :resolved (keys depths)}))
            (let [new-depths (reduce
                               (fn [acc id]
                                 (let [dep-depths (map #(get acc %) (get deps id))
                                       max-dep (if (empty? dep-depths) -1 (apply max dep-depths))]
                                   (assoc acc id (inc max-dep))))
                               depths
                               ready)]
              (recur new-depths (set/difference remaining (set ready))))))))))

;; =============================================================================
;; Parallel Execution Levels
;; =============================================================================

(defn compute-execution-levels
  "Group steps into execution levels where all steps in a level can run in parallel.

   Steps in level N depend only on steps in levels 0 to N-1.

   Args:
     graph - Graph definition

   Returns: Vector of vectors, where each inner vector is a level of parallel steps
            [[level-0-steps] [level-1-steps] ...]"
  [graph]
  (let [depths (get-step-depth graph)
        max-depth (if (empty? depths) 0 (apply max (vals depths)))]
    (mapv
      (fn [level]
        (->> depths
             (filter (fn [[_ d]] (= d level)))
             (map first)
             vec))
      (range (inc max-depth)))))

(defn get-parallelization-factor
  "Calculate the maximum parallelization factor for a graph.

   This is the maximum number of steps that can run concurrently.

   Args:
     graph - Graph definition

   Returns: Integer representing max parallel steps"
  [graph]
  (let [levels (compute-execution-levels graph)]
    (if (empty? levels)
      0
      (apply max (map count levels)))))

;; =============================================================================
;; Output Dependency Analysis
;; =============================================================================

(defn find-required-outputs
  "Find which step outputs are actually used by downstream steps or graph outputs.

   Args:
     graph - Graph definition

   Returns: Map of step-id -> #{required-output-keys}"
  [graph]
  (let [steps (:steps graph)
        graph-outputs (set (:outputs graph))
        step-map (into {} (map (juxt :id identity) steps))]

    ;; Build map of step -> required output keys
    (reduce
      (fn [acc step]
        (reduce-kv
          (fn [inner-acc _input-name ref]
            (cond
              ;; Reference to specific output key: [:step-id :key]
              (and (vector? ref) (= 2 (count ref)))
              (let [[step-id output-key] ref]
                (update inner-acc step-id (fnil conj #{}) output-key))

              ;; Reference to entire step output (need all outputs)
              (and (keyword? ref) (not (schema/graph-input? ref)))
              (let [referenced-step (get step-map ref)]
                (if referenced-step
                  ;; We need to track that something references this step
                  ;; but we don't know which specific outputs
                  (update inner-acc ref (fnil identity :all))
                  inner-acc))

              :else inner-acc))
          acc
          (:inputs step)))
      {}
      steps)))

(defn find-unused-steps
  "Find steps whose outputs are never used.

   Note: Steps may have side effects, so unused doesn't mean removable.

   Args:
     graph - Graph definition

   Returns: Set of step-ids that have no downstream consumers"
  [graph]
  (let [reverse-deps (build-reverse-dependency-map graph)
        graph-output-steps (set (map :id (:steps graph)))] ; All steps could produce graph outputs
    (->> reverse-deps
         (filter (fn [[_step-id dependents]] (empty? dependents)))
         (map first)
         set)))

;; =============================================================================
;; Critical Path Analysis
;; =============================================================================

(defn compute-critical-path
  "Find the critical path - the longest chain of dependent steps.

   Args:
     graph - Graph definition

   Returns: Vector of step-ids representing the critical path"
  [graph]
  (let [deps (build-dependency-map graph)
        depths (get-step-depth graph)
        max-depth (if (empty? depths) -1 (apply max (vals depths)))]

    (when (>= max-depth 0)
      ;; Start from a step at max depth and trace back
      (let [end-step (first (filter #(= max-depth (get depths %)) (keys depths)))]
        (loop [path [end-step]
               current end-step]
          (let [current-deps (get deps current)
                current-depth (get depths current)]
            (if (or (empty? current-deps) (zero? current-depth))
              (reverse path)
              ;; Find dependency at depth - 1
              (let [prev-step (first (filter #(= (dec current-depth) (get depths %))
                                             current-deps))]
                (recur (conj path prev-step) prev-step)))))))))

;; =============================================================================
;; Execution Plan
;; =============================================================================

(defrecord ExecutionPlan
  [graph
   levels           ; Vector of parallel execution levels
   depths           ; Map of step-id -> depth
   dependencies     ; Map of step-id -> #{dependencies}
   dependents       ; Map of step-id -> #{dependents}
   critical-path    ; Vector of step-ids on critical path
   parallelization  ; Max parallel factor
   total-steps])    ; Total number of steps

(defn create-execution-plan
  "Create an optimized execution plan for a graph.

   The execution plan pre-computes all dependency information
   to enable efficient parallel execution.

   Args:
     graph - Validated graph definition

   Returns: ExecutionPlan record"
  [graph]
  (schema/fully-validate-graph! graph)
  (let [levels (compute-execution-levels graph)
        depths (get-step-depth graph)
        deps (build-dependency-map graph)
        rev-deps (build-reverse-dependency-map graph)
        critical (compute-critical-path graph)
        parallel (get-parallelization-factor graph)]
    (->ExecutionPlan
      graph
      levels
      depths
      deps
      rev-deps
      critical
      parallel
      (count (:steps graph)))))

(defn get-ready-steps
  "Get steps that are ready to execute given completed steps.

   Args:
     plan - ExecutionPlan
     completed - Set of completed step-ids

   Returns: Set of step-ids ready to execute"
  [plan completed]
  (let [deps (:dependencies plan)
        all-steps (set (keys deps))]
    (->> all-steps
         (remove completed)
         (filter (fn [step-id]
                   (every? completed (get deps step-id #{}))))
         set)))

(defn estimate-parallel-speedup
  "Estimate the theoretical speedup from parallel execution.

   Assumes all steps take equal time. Real speedup depends on
   actual step durations.

   Args:
     plan - ExecutionPlan

   Returns: Map with :sequential-steps, :parallel-levels, :theoretical-speedup"
  [plan]
  (let [total (:total-steps plan)
        levels (count (:levels plan))]
    {:sequential-steps total
     :parallel-levels levels
     :theoretical-speedup (if (zero? levels)
                            1.0
                            (double (/ total levels)))}))

;; =============================================================================
;; Graph Optimization Suggestions
;; =============================================================================

(defn analyze-graph
  "Analyze a graph and return optimization suggestions.

   Args:
     graph - Graph definition

   Returns: Map with analysis results and suggestions"
  [graph]
  (let [plan (create-execution-plan graph)
        unused (find-unused-steps graph)
        speedup (estimate-parallel-speedup plan)]
    {:execution-plan plan
     :analysis
     {:total-steps (:total-steps plan)
      :parallel-levels (count (:levels plan))
      :max-parallelization (:parallelization plan)
      :critical-path-length (count (:critical-path plan))
      :critical-path (:critical-path plan)
      :theoretical-speedup (:theoretical-speedup speedup)
      :unused-steps unused}
     :suggestions
     (cond-> []
       (> (:parallelization plan) 1)
       (conj {:type :parallelization
              :message (str "Graph can run up to " (:parallelization plan)
                            " steps in parallel")
              :benefit :performance})

       (seq unused)
       (conj {:type :unused-steps
              :message (str "Steps " unused " have no downstream consumers")
              :steps unused
              :benefit :clarity})

       (= 1 (:parallelization plan))
       (conj {:type :linear-graph
              :message "Graph is fully sequential - consider if steps can be parallelized"
              :benefit :performance}))}))

;; =============================================================================
;; Optimized Execution (Future)
;; =============================================================================

(defn execution-levels-seq
  "Return a lazy sequence of execution levels from a plan.

   Each level is a vector of step-ids that can run in parallel.

   Args:
     plan - ExecutionPlan

   Returns: Lazy sequence of step-id vectors"
  [plan]
  (seq (:levels plan)))
