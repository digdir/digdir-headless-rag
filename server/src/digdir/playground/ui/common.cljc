(ns digdir.playground.ui.common
  "Common helper functions and server-side data functions for the RAG Playground UI."
  (:require [clojure.string :as str]
            [digdir.playground.ui.components :as base]
            #?(:clj [digdir.config.accessor :as cfg])
            #?(:clj [digdir.config.core :as config-core])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.skills.api :as skills-api])
            [digdir.playground.ui.observability :as observability]
            [digdir.playground.diagnostics :as diagnostics]))

;; =============================================================================
;; Skill Graph Constants & Helpers
;; =============================================================================

(def default-skill-graphs
  [{:value "builtin/fact-checker" :label "Fact Checker"}
   {:value "builtin/agent-rag-graph-bundled" :label "Agentic RAG (graph, bundled)"}
   {:value "builtin/agent-rag-graph-faithful" :label "Agentic RAG (graph, faithful)"}])

(defn normalize-skill-graph-option
  "Normalize a skill graph map into a UI option map."
  [sg]
  (let [id (:id sg)
        value (cond
                (keyword? id) (if-let [ns (namespace id)]
                                (str ns "/" (name id))
                                (name id))
                (string? id) id
                :else nil)
        label (or (:name sg) value "Unnamed Skill Graph")]
    {:value (or value "builtin/agent-rag-graph-bundled")
     :label label}))

(defn normalize-dataset-scope-option
  "Normalize dataset option payloads crossing the Electric boundary.

   Accepts keyword-key maps, string-key maps, [value label] tuples, or plain strings."
  [option]
  (let [option-map (when (map? option) option)
        tuple? (and (vector? option) (= 2 (count option)))
        raw-value (cond
                    tuple? (first option)
                    option-map (or (:value option-map)
                                   (get option-map "value")
                                   (:dataset-config-key option-map)
                                   (get option-map "dataset-config-key")
                                   (get option-map "dataset_config_key"))
                    (or (string? option) (keyword? option)) option
                    :else nil)
        raw-label (cond
                    tuple? (second option)
                    option-map (or (:label option-map)
                                   (get option-map "label")
                                   (:dataset-name option-map)
                                   (get option-map "dataset-name")
                                   (:name option-map)
                                   (get option-map "name"))
                    :else nil)
        value (cond
                (keyword? raw-value) (name raw-value)
                (string? raw-value) raw-value
                (some? raw-value) (str raw-value)
                :else nil)
        label (cond
                (keyword? raw-label) (name raw-label)
                (string? raw-label) raw-label
                (some? raw-label) (str raw-label)
                :else nil)]
    {:value value
     :label (or (not-empty label)
                (not-empty value)
                "Unnamed dataset")}))

#?(:clj
   (defn load-skill-graph-options-data
     "Load skill graph options and debug metadata for the Playground config panel."
     []
     (try
       (skills-api/initialize!)
       (let [raw (vec (skills-api/list-skill-graphs))
             loaded (->> raw
                         (mapv normalize-skill-graph-option)
                         (filterv #(and (not (str/blank? (:value %)))
                                        (not (str/blank? (:label %))))))]
         (if (seq loaded)
           {:options loaded
            :debug {:source :registry
                    :raw-count (count raw)
                    :option-count (count loaded)
                    :raw-preview (mapv (fn [sg] (select-keys sg [:id :name])) (take 5 raw))}}
           {:options default-skill-graphs
            :debug {:source :fallback-empty
                    :raw-count (count raw)
                    :option-count (count default-skill-graphs)
                    :raw-preview (mapv (fn [sg] (select-keys sg [:id :name])) (take 5 raw))}}))
       (catch Exception e
         (println "Playground skill graph load failed:" (.getMessage e))
         {:options default-skill-graphs
          :debug {:source :fallback-error
                  :raw-count 0
                  :option-count (count default-skill-graphs)
                  :error (.getMessage e)}}))))

;; =============================================================================
;; Chat Configuration Helpers
;; =============================================================================

(defn default-chat-config
  "Default chat config without dataset-scope-specific retrieval/rerank overrides.
   :model defaults to nil so the synthesis skill can resolve from runtime config
   (skills.synthesis.model) or the per-tenant Azure deployment fallback. The
   ConfigPanel displays the resolved default and only sets :model when the
   user actively picks a non-default option."
  []
  {:model nil
   :temperature 0.1
   :max-tokens 4096
   :rerank-top-k nil
   :context-top-k nil
   :rerank-threshold nil
   :skill-graph "builtin/agent-rag-graph-bundled"})

(defn initialize-chat-config
  "Initialize chat config from the selected dataset's resolved defaults."
  [config dataset-config]
  (let [base (merge (default-chat-config) (or config {}))]
    (if dataset-config
      (assoc base
             :rerank-top-k (:rerank-top-k dataset-config)
             ;; Playground runs full-RAG flows; surface :rag-* values.
             :context-top-k (:rerank-rag-context-top-k dataset-config)
             :rerank-max-chunk-length (:rerank-rag-max-chunk-length dataset-config)
             :rerank-max-total-length (:rerank-rag-max-total-length dataset-config)
             :context-max-chunk-length (:rerank-rag-context-max-chunk-length dataset-config)
             :context-max-total-length (:rerank-rag-max-context-length dataset-config)
             :prompt-query-relax (:query-planner-prompt dataset-config)
             :prompt-rag-generate (:synthesis-generation-prompt dataset-config)
             ;; Default model surfaced in the picker. The synthesis skill's
             ;; full resolution chain is :model parameter > runtime
             ;; :synthesis-model > Azure deployment-name; we surface the
             ;; runtime layer here since that's the operator's lever.
             :default-model (:synthesis-model dataset-config)
             ;; Tenant-specific list of Azure deployment names (fetched
             ;; server-side and cached for 60s). nil when not Azure / API
             ;; call failed → ConfigPanel falls back to a hardcoded list.
             :available-models (:available-models dataset-config))
      base)))

(defn effective-chat-config
  "Merge the current config with the resolved skill graph without mutating UI state."
  [config effective-skill-graph]
  (cond-> (merge (default-chat-config) (or config {}))
    (not (str/blank? effective-skill-graph)) (assoc :skill-graph effective-skill-graph)))

;; =============================================================================
;; Dataset & Agent Helpers
;; =============================================================================

#?(:clj
   (defn- dataset-scope-usable?
     [tenant dataset-config-key]
     (try
       (some? (config-db/get-dataset-by-ref @(config-db/get-conn)
                                            {:tenant tenant
                                             :dataset-config-key dataset-config-key}
                                            (config-core/get-master-key)))
       (catch clojure.lang.ExceptionInfo _
         false)
       (catch Exception _
         false))))

#?(:clj
   (defn- resolve-dataset-scope-option
     [tenant dataset-config-key]
     (let [conn @(config-db/get-conn)
           master-key (config-core/get-master-key)]
       (try
         (when-let [dataset-config (config-db/get-dataset-by-ref conn
                                                                 {:tenant tenant
                                                                  :dataset-config-key dataset-config-key}
                                                                 master-key)]
           (let [dataset-record (some->> (:dataset-id dataset-config)
                                         (config-db/get-dataset-record conn))
                 node (or (when-let [dataset-node-id (:dataset-node-id dataset-config)]
                            (config-db/get-config-node conn dataset-node-id))
                          (config-db/get-config-node-by-tenant-config-key conn tenant :dataset dataset-config-key))]
             {:value (or (:dataset-config-key dataset-config)
                         dataset-config-key)
              :dataset-id (:dataset-id dataset-config)
              :dataset-name (:dataset/name dataset-record)
              :node-label (:config.node/label node)}))
         (catch clojure.lang.ExceptionInfo _
           nil)
         (catch Exception _
           nil)))))

#?(:clj
   (defn- dataset-scope-option-label
     [{:keys [value dataset-name node-label]}]
     (cond
       (not (str/blank? dataset-name))
       dataset-name

       (and (not (str/blank? node-label))
            (not= node-label value))
       (str node-label " (" value ")")

       :else
       value)))

#?(:clj
   (defn- dataset-scope-option-priority
     [{:keys [value dataset-id dataset-name node-label]}]
     [(cond
        (= value dataset-id) 0
        (= value "default") 1
        (= value (str dataset-id "-materialization")) 2
        (= node-label dataset-name) 3
        :else 4)
      value]))

#?(:clj
   (defn- canonical-dataset-scope-option
     [resolved-options]
     (first (sort-by dataset-scope-option-priority resolved-options))))

#?(:clj
   (defn- tenant-dataset-scope-candidate-keys
     [tenant]
     (let [conn @(config-db/get-conn)]
       (->> (config-db/list-datasets conn tenant nil)
            (keep (fn [pipeline-id]
                    (some-> (config-db/get-dataset-pipeline conn pipeline-id)
                            :dataset.pipeline/dataset
                            :dataset/id)))
            distinct
            sort
            vec))))

#?(:clj
   (defn- resolve-playground-runtime-config
     [tenant agent-id]
     (when-not (str/blank? agent-id)
       (let [runtime-config-key "default"]
         (try
           (:config (cfg/get-runtime-skill-config-v2-with-trace
                     {:tenant tenant
                      :tenant-config-key runtime-config-key
                      :agent-id agent-id}))
           (catch clojure.lang.ExceptionInfo _
             nil)
           (catch Exception _
             nil))))))

#?(:clj
   (defn- resolve-available-models
     "Best-effort fetch of the tenant's Azure deployment list for the playground
      model picker. Returns nil for non-Azure tenants or on any failure;
      callers fall back to a hardcoded list."
     [tenant]
     (try
       ((requiring-resolve 'digdir.llm.azure-deployments/list-deployment-names)
        tenant)
       (catch Exception _
         nil))))

#?(:clj
   (defn resolve-selected-dataset-config
     "Load the selected dataset plus V2 runtime skill defaults when agent scope is available.
      This no longer reads legacy tuple-scoped runtime skill config."
     [tenant dataset-config-key agent-id]
     (when (and tenant dataset-config-key)
       (try
         (let [master-key (config-core/get-master-key)
               dataset-scope {:tenant tenant
                              :dataset-config-key dataset-config-key}
               dataset-config (config-db/get-dataset-by-ref @(config-db/get-conn)
                                                            dataset-scope
                                                            master-key)
               runtime-config (when dataset-config
                                (resolve-playground-runtime-config tenant agent-id))
               available-models (when dataset-config
                                  (resolve-available-models tenant))]
           (when dataset-config
             (cond-> (merge {:dataset-config-key (or (:dataset-config-key dataset-config)
                                                     dataset-config-key)}
                            dataset-config
                            runtime-config)
               (seq available-models) (assoc :available-models available-models))))
         (catch clojure.lang.ExceptionInfo e
           (println "Playground dataset selection skipped:" (.getMessage e) (pr-str (ex-data e)))
           nil)
         (catch Exception e
           (println "Playground dataset selection failed:" (.getMessage e))
           nil)))))

(defn dataset-scope-label
  "Render a persisted dataset scope as tenant / dataset-config-key."
  [{:keys [tenant dataset-config-key]}]
  (->> [tenant dataset-config-key]
       (remove str/blank?)
       (str/join " / ")))

(defn conversation-dataset-scope
  "Project a persisted conversation onto the canonical persisted dataset scope."
  [conversation]
  {:tenant (:conversation/tenant conversation)
   :dataset-config-key (:conversation/dataset-config-key conversation)})

(defn conversation-scope-label
  "Display conversations as agent-first, with dataset scope as context."
  [conversation agent-names]
  (let [agent-id (:conversation/agent-id conversation)
        agent-label (or (clojure.core/get agent-names agent-id) agent-id)
        dataset-label (dataset-scope-label (conversation-dataset-scope conversation))]
    (cond
      (and (not (str/blank? agent-label))
           (not (str/blank? dataset-label)))
      (str agent-label " • " dataset-label)

      (not (str/blank? agent-label))
      agent-label

      (not (str/blank? dataset-label))
      dataset-label

      :else
      "Unscoped conversation")))

(defn preferred-agent-id
  "Choose the effective playground agent from explicit selection, then skill graph, then first enabled agent."
  [selected-agent-id config enabled-agents]
  (let [enabled-agent-ids (mapv :id enabled-agents)
        valid-agent-ids (set enabled-agent-ids)
        skill-graph-id (:skill-graph config)
        skill-graph-agent-id (or (some (fn [agent]
                                         (when (= skill-graph-id (:default-skill-graph agent))
                                           (:id agent)))
                                       enabled-agents)
                                 (some (fn [agent]
                                         (when (some #{skill-graph-id} (:allowed-skill-graphs agent))
                                           (:id agent)))
                                       enabled-agents))]
    (cond
      (contains? valid-agent-ids selected-agent-id) selected-agent-id
      (contains? valid-agent-ids skill-graph-agent-id) skill-graph-agent-id
      (seq enabled-agent-ids) (first enabled-agent-ids)
      :else nil)))

(defn displayed-agent-id
  "Choose the agent ID to show in the selector. Prefer the explicit selection when it is still valid."
  [selected-agent-id effective-agent-id enabled-agents]
  (let [valid-agent-ids (set (map :id enabled-agents))]
    (cond
      (contains? valid-agent-ids selected-agent-id) selected-agent-id
      (contains? valid-agent-ids effective-agent-id) effective-agent-id
      :else (or selected-agent-id effective-agent-id))))

(defn restrict-skill-graph-data
  "Limit visible skill graph options to those allowed by the selected agent."
  [skill-graph-data agent]
  (let [allowed-graphs (set (:allowed-skill-graphs agent))]
    (if (seq allowed-graphs)
      (update skill-graph-data
              :options
              (fn [options]
                (let [filtered (filterv #(contains? allowed-graphs (:value %)) options)]
                  (if (seq filtered) filtered options))))
      skill-graph-data)))

(defn effective-skill-graph-id
  "Pick a valid skill graph for the selected agent and currently loaded options."
  [config skill-graph-data agent]
  (let [current-skill-graph (:skill-graph config)
        option-values (set (keep :value (:options skill-graph-data)))
        default-skill-graph (:default-skill-graph agent)]
    (cond
      (contains? option-values current-skill-graph) current-skill-graph
      (contains? option-values default-skill-graph) default-skill-graph
      (seq option-values) (:value (first (:options skill-graph-data)))
      :else current-skill-graph)))

(defn dataset-scope-allowed?
  "True when a selected dataset scope is permitted by an agent's explicit dataset grants."
  [dataset-scopes tenant dataset-config-key]
  (or (empty? dataset-scopes)
      (some (fn [dataset-scope]
              (and (= tenant (:tenant dataset-scope))
                   (= dataset-config-key (:dataset-config-key dataset-scope))))
            dataset-scopes)))

(defn allowed-option-values
  "List visible values for one dataset dimension from explicit allowed dataset scopes."
  [dataset-scopes k]
  (->> dataset-scopes
       (map k)
       (remove str/blank?)
       distinct
       sort
       vec))

(defn dataset-scope-options
  "Build visible dataset-scope options for a tenant."
  [tenant agent-dataset-scopes]
  #?(:clj
     (if (str/blank? tenant)
       []
       (let [allowed-keys (->> agent-dataset-scopes
                               (filter #(= tenant (:tenant %)))
                               (map :dataset-config-key)
                               (remove str/blank?)
                               distinct
                               vec)
             candidate-keys (if (seq allowed-keys)
                              allowed-keys
                              (tenant-dataset-scope-candidate-keys tenant))
             resolved-options (->> candidate-keys
                                   (keep (fn [config-key]
                                           (when (dataset-scope-usable? tenant config-key)
                                             (resolve-dataset-scope-option tenant config-key))))
                                   vec)
             canonical-options (->> resolved-options
                                    (group-by (fn [{:keys [dataset-id value]}]
                                                (or dataset-id value)))
                                    vals
                                    (mapv canonical-dataset-scope-option))]
         (->> canonical-options
              (mapv (fn [option]
                      {:value (:value option)
                       :label (dataset-scope-option-label option)}))
              (sort-by :label)
              vec)))
     :cljs
     (->> agent-dataset-scopes
          (filter #(= tenant (:tenant %)))
          (keep (fn [dataset-scope]
                  (let [dataset-config-key (:dataset-config-key dataset-scope)]
                    (when-not (str/blank? dataset-config-key)
                      {:value dataset-config-key
                       :label dataset-config-key}))))
          (sort-by :label)
          vec)))

(def dataset-ref-label dataset-scope-label)
(def conversation-dataset-ref conversation-dataset-scope)
(def dataset-ref-allowed? dataset-scope-allowed?)
(def dataset-config-options dataset-scope-options)

;; =============================================================================
;; Client-side State
;; =============================================================================

#?(:cljs
   (defonce !playground-chat-state
     (atom {:conversation-id nil
            :selected-agent-id nil   ; Selected agent for the conversation
            :selected-tenant nil      ; Selected tenant for scope
            :selected-dataset-config-key nil ; Selected dataset-config-key for scope
            :query ""
            :config (default-chat-config)
            :execution-id nil
            :show-sidebar true
            :active-branch-path {}    ; Map of parent-msg-id -> selected child index
           :editing-msg-id nil       ; Message ID being edited
           :editing-text ""          ; Text being edited
           :compare-mode false       ; Whether compare mode is active
           :compare-execution-ids [] ; Execution IDs to compare
            ;; Action signals (set by callbacks, consumed by main component)
            :pending-send nil         ; Query text to send
            :pending-regenerate nil   ; {:parent-id ... :query ...} to regenerate
            :pending-edit-submit nil})))  ; {:parent-id ... :query ... :branch-idx ...}

;; =============================================================================
;; Legacy / Component Wrappers (Non-UI logic)
;; =============================================================================

(defn render-markdown-to-html [content]
  #?(:clj (base/render-markdown-to-html content)
     :cljs content))

(defn render-inline-markdown-to-html [content]
  #?(:clj (base/render-inline-markdown-to-html content)
     :cljs content))

(defn metadata->markdown [metadata]
  #?(:clj (base/metadata->markdown metadata)
     :cljs (when metadata (str metadata))))

(defn render-markdown-with-citations [content]
  #?(:clj (base/render-markdown-with-citations content)
     :cljs content))

(defn agent-status-messages [diagnostics]
  (observability/agent-status-messages diagnostics))

(defn normalize-debug-playground-mode [mode]
  (observability/normalize-debug-playground-mode mode))

(defn format-retrieval-filter-label [entry]
  (diagnostics/format-retrieval-filter-label entry))

(defn retrieval-filter-entries [diagnostics]
  (diagnostics/retrieval-filter-entries diagnostics))

#?(:clj
   (defn safe-probe
     "Run a plain server-side probe and return either a summarized value or error payload."
     [label f]
     (try
       {:label label
        :ok true
        :value (f)}
       (catch Throwable t
         {:label label
          :ok false
          :error {:class (str (class t))
                  :message (.getMessage t)}}))))
