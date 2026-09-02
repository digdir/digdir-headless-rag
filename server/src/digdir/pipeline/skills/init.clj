(ns digdir.pipeline.skills.init
  "Skill system initialization.

   This namespace provides functions to initialize the skill system,
   register all built-in skills and templates, and verify the system
   is ready for use."
  (:require [digdir.rag.skills.core :as skills-core]
            [digdir.pipeline.templates.core :as templates-core]
            ;; Built-in skills
            [digdir.pipeline.skills.builtin.retrieval :as retrieval]
            [digdir.pipeline.skills.builtin.rerank :as rerank]
            [digdir.pipeline.skills.builtin.synthesis :as synthesis]
            [digdir.pipeline.skills.builtin.query-planner :as query-planner]
            [digdir.pipeline.skills.builtin.entity-extraction :as entity-extraction]
            [digdir.pipeline.skills.builtin.fact-checking :as fact-checking]
            [digdir.pipeline.skills.builtin.summarization :as summarization]
            [digdir.pipeline.skills.builtin.multi-retrieval :as multi-retrieval]
            [digdir.pipeline.skills.builtin.graph-builder :as graph-builder]
            ;; Built-in templates
            [digdir.pipeline.templates.builtin :as builtin-templates]))

;; =============================================================================
;; Initialization State
;; =============================================================================

(defonce ^{:private true
           :doc "Tracks initialization state"}
  !init-state
  (atom {:initialized false
         :skills-registered 0
         :templates-registered 0
         :initialized-at nil}))

;; =============================================================================
;; Skill Registration
;; =============================================================================

(def builtin-skill-namespaces
  "List of built-in skill namespaces with their register functions."
  [{:ns 'digdir.pipeline.skills.builtin.retrieval
    :skill-id :builtin/retrieval
    :register-fn retrieval/register!}
   {:ns 'digdir.pipeline.skills.builtin.rerank
    :skill-id :builtin/rerank
    :register-fn rerank/register!}
   {:ns 'digdir.pipeline.skills.builtin.synthesis
    :skill-id :builtin/synthesis
    :register-fn synthesis/register!}
   {:ns 'digdir.pipeline.skills.builtin.query-planner
    :skill-id :builtin/query-planner
    :register-fn query-planner/register!}
   {:ns 'digdir.pipeline.skills.builtin.entity-extraction
    :skill-id :builtin/entity-extraction
    :register-fn entity-extraction/register!}
   {:ns 'digdir.pipeline.skills.builtin.fact-checking
    :skill-id :builtin/fact-checking
    :register-fn fact-checking/register!}
   {:ns 'digdir.pipeline.skills.builtin.summarization
    :skill-id :builtin/summarization
    :register-fn summarization/register!}
   {:ns 'digdir.pipeline.skills.builtin.multi-retrieval
    :skill-id :builtin/multi-retrieval
    :register-fn multi-retrieval/register!}
   {:ns 'digdir.pipeline.skills.builtin.graph-builder
    :skill-id :builtin/graph-builder
    :register-fn graph-builder/register!}])

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
;; Template Registration
;; =============================================================================

(defn register-builtin-templates!
  "Register all built-in templates.

   Returns: Count of templates registered"
  []
  (builtin-templates/register-all!)
  (count (templates-core/list-templates)))

;; =============================================================================
;; Initialization
;; =============================================================================

(defn initialize!
  "Initialize the skill system.

   Registers all built-in skills and templates.
   Safe to call multiple times - will only initialize once.

   Returns: Initialization state map"
  []
  (if (:initialized @!init-state)
    @!init-state
    (let [skills-count (register-builtin-skills!)
          templates-count (register-builtin-templates!)
          state {:initialized true
                 :skills-registered skills-count
                 :templates-registered templates-count
                 :initialized-at (System/currentTimeMillis)}]
      (reset! !init-state state)
      state)))

(defn ensure-initialized!
  "Ensure the system is initialized, initializing if needed.

   Returns: true"
  []
  (when-not (:initialized @!init-state)
    (initialize!))
  true)

(defn reset-skills!
  "Reset the skill system to uninitialized state.

   Clears all registrations. Useful for testing."
  []
  (skills-core/clear-registry!)
  (templates-core/clear-registry!)
  (reset! !init-state {:initialized false
                       :skills-registered 0
                       :templates-registered 0
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
             :current-templates (count (templates-core/list-templates))
             :skill-ids (vec (skills-core/list-skill-ids))
             :template-ids (vec (templates-core/list-template-ids)))
      state)))

(defn verify-skills
  "Verify all expected skills are registered.

   Returns: Map with :ok and :missing keys"
  []
  (ensure-initialized!)
  (let [expected-ids (set (map :skill-id builtin-skill-namespaces))
        registered-ids (set (skills-core/list-skill-ids))
        missing (clojure.set/difference expected-ids registered-ids)]
    (if (empty? missing)
      {:ok true :count (count registered-ids)}
      {:ok false :missing (vec missing) :count (count registered-ids)})))

(defn verify-templates
  "Verify all expected templates are registered.

   Returns: Map with :ok and :missing keys"
  []
  (ensure-initialized!)
  (let [expected-ids #{:builtin/simple-qa :builtin/research-assistant :builtin/fact-checker}
        registered-ids (set (templates-core/list-template-ids))
        missing (clojure.set/difference expected-ids registered-ids)]
    (if (empty? missing)
      {:ok true :count (count registered-ids)}
      {:ok false :missing (vec missing) :count (count registered-ids)})))

(defn health-check
  "Perform a health check on the skill system.

   Returns: Health status map"
  []
  (let [skills-status (verify-skills)
        templates-status (verify-templates)]
    {:healthy (and (:ok skills-status) (:ok templates-status))
     :skills skills-status
     :templates templates-status
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
