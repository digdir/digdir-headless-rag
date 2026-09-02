(ns digdir.pipeline.skills.graph.schema
  "Malli schemas for skill graph validation.

   A skill graph defines a workflow of skill executions where:
   - Each step invokes a skill with mapped inputs
   - Inputs can come from graph inputs (:$var) or previous step outputs (:step-id)
   - Steps form a DAG (directed acyclic graph)"
  (:require [malli.core :as m]
            [malli.error :as me]
            [clojure.set :as set]))

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

(def GraphStep
  "Schema for a single step in a skill graph."
  [:map
   {:doc "A single skill execution step in the graph"}
   [:id keyword?]
   [:skill keyword?]
   [:inputs [:map-of keyword? InputRef]]
   [:parameters {:optional true} [:map-of keyword? any?]]
   [:condition {:optional true} [:or keyword? fn?]]
   [:on-error {:optional true} [:enum :skip :fail :default]]])

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

(defn extract-input-refs
  "Extract all input references from a step."
  [step]
  (->> (:inputs step)
       vals
       (mapv (fn [ref]
               (cond
                 (vector? ref) (first ref)  ; [:step-id :key]
                 (keyword? ref) ref
                 :else nil)))
       (remove nil?)
       set))

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
        step-map (into {} (map (juxt :id identity) steps))
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
