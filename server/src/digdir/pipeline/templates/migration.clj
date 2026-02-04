(ns digdir.pipeline.templates.migration
  "Migration utilities for transitioning to skill-based RAG.

   Maps existing entity retrieval/generation configs to skill parameters
   and creates templates that match current RAG behavior."
  (:require [digdir.config.db :as config-db]
            [digdir.pipeline.templates.core :as templates]
            [digdir.pipeline.templates.builtin :as builtin]))

;; =============================================================================
;; Entity Config Mapping
;; =============================================================================

(def entity-to-skill-param-mapping
  "Maps entity properties to skill parameters.

   Entity properties (from config-db/entity-property-to-path):
   - :rerank-enabled → :builtin/rerank :enabled
   - :rerank-top-k → :builtin/rerank :top-k
   - :rerank-max-chunk-length → :builtin/rerank :max-chunk-length
   - :rerank-max-total-length → :builtin/rerank :max-total-length
   - :context-top-k → :builtin/synthesis :context-top-k
   - :context-max-docs → :builtin/synthesis :max-docs
   - :context-max-chunk-length → :builtin/synthesis :max-chunk-length
   - :context-max-total-length → :builtin/synthesis :max-context
   - :prompt-query-relax → :builtin/query-planner :prompt
   - :prompt-rag-generate → :builtin/synthesis :generation-prompt"
  {:rerank-enabled [:builtin/rerank :enabled]
   :rerank-top-k [:builtin/rerank :top-k]
   :rerank-max-chunk-length [:builtin/rerank :max-chunk-length]
   :rerank-max-total-length [:builtin/rerank :max-total-length]
   :context-top-k [:builtin/rerank :context-top-k]
   :context-max-docs [:builtin/synthesis :max-docs]
   :context-max-chunk-length [:builtin/rerank :context-max-chunk-length]
   :context-max-total-length [:builtin/synthesis :max-context]
   :prompt-query-relax [:builtin/query-planner :prompt]
   :prompt-rag-generate [:builtin/synthesis :generation-prompt]})

(def collection-mapping
  "Maps entity collection properties to standard input names.

   Entity properties:
   - :docs-collection → :docs-collection
   - :chunks-collection → :chunks-collection
   - :phrases-collection → :phrases-collection"
  {:docs-collection :docs-collection
   :chunks-collection :chunks-collection
   :phrases-collection :phrases-collection})

;; =============================================================================
;; Config Extraction
;; =============================================================================

(defn extract-entity-config
  "Extract RAG-relevant config from an entity.

   Args:
     db - Datahike database value
     tenant - Tenant identifier
     environment - Environment
     entity-id - Entity ID
     master-key - Encryption key

   Returns: Map of entity properties relevant to RAG"
  [db tenant environment entity-id master-key]
  (let [entity (config-db/get-entity db tenant environment entity-id master-key)]
    (select-keys entity [:docs-collection
                         :chunks-collection
                         :phrases-collection
                         :rerank-enabled
                         :rerank-top-k
                         :rerank-max-chunk-length
                         :rerank-max-total-length
                         :context-top-k
                         :context-max-docs
                         :context-max-chunk-length
                         :context-max-total-length
                         :prompt-query-relax
                         :prompt-rag-generate])))

(defn map-entity-to-skill-params
  "Map entity config to skill parameters.

   Args:
     entity-config - Entity config map

  Returns: Map of {skill-id -> {param -> value}}"
  [entity-config]
  (reduce-kv
    (fn [acc entity-prop value]
      (if-let [[skill-id param] (get entity-to-skill-param-mapping entity-prop)]
        (assoc-in acc [skill-id param] value)
        acc))
    {}
    entity-config))

;; =============================================================================
;; Template Creation from Entity
;; =============================================================================

(defn create-entity-template
  "Create a template from an entity's current config.

   This creates a simple-qa style template that matches the entity's
   current behavior, allowing seamless migration.

   Args:
     entity-config - Entity config map from extract-entity-config
     entity-id - Entity ID for naming

   Returns: Template definition"
  [entity-config entity-id]
  (let [skill-params (map-entity-to-skill-params entity-config)
        collections (select-keys entity-config [:docs-collection
                                                :chunks-collection
                                                :phrases-collection])
        rerank-enabled? (get entity-config :rerank-enabled true)]
    {:id (keyword (str "migrated/" entity-id))
     :name (str "Migrated: " entity-id)
     :description (str "Auto-migrated template from entity " entity-id)
     :graph {:id (keyword (str "migrated/" entity-id "-graph"))
             :name (str entity-id " RAG Pipeline")
             :description "Migrated RAG pipeline"
             :inputs [:user-query]
             :outputs [:response :chunks]
             :steps (cond-> [{:id :plan
                              :skill :builtin/query-planner
                              :inputs {:query :$user-query}
                              :parameters (get skill-params :builtin/query-planner {})}
                             {:id :retrieve
                              :skill :builtin/retrieval
                              :inputs {:queries [:plan :search-phrases]
                                       :docs-collection (:docs-collection collections)
                                       :chunks-collection (:chunks-collection collections)
                                       :phrases-collection (:phrases-collection collections)}}]
                      rerank-enabled?
                      (conj {:id :rerank
                             :skill :builtin/rerank
                             :inputs {:chunks [:retrieve :chunks]
                                      :query :$user-query
                                      :docs-collection (:docs-collection collections)}
                             :parameters (get skill-params :builtin/rerank {})})

                      true
                      (conj {:id :generate
                             :skill :builtin/synthesis
                             :inputs {:query :$user-query
                                      :context-docs (if rerank-enabled?
                                                     [:rerank :context-docs]
                                                     [:retrieve :chunks])}
                             :parameters (get skill-params :builtin/synthesis {})}))}
     :parameters skill-params
     :version "1.0.0"
     :tags #{:migrated entity-id}}))

;; =============================================================================
;; Migration Functions
;; =============================================================================

(defn migrate-entity-to-template!
  "Migrate an entity's RAG config to a template.

   Args:
     conn - Datahike connection
     tenant - Tenant identifier
     environment - Environment
     entity-id - Entity ID
     master-key - Encryption key

   Returns: Registered template"
  [conn tenant environment entity-id master-key]
  (let [db @conn
        entity-config (extract-entity-config db tenant environment entity-id master-key)
        template (create-entity-template entity-config entity-id)]
    (templates/register-template! template)))

(defn migrate-all-entities!
  "Migrate all entities for a tenant to templates.

   Args:
     conn - Datahike connection
     tenant - Tenant identifier
     environment - Environment
     master-key - Encryption key

   Returns: Vector of registered template IDs"
  [conn tenant environment master-key]
  (let [db @conn
        entity-ids (config-db/list-entities db tenant)]
    (mapv #(do
             (migrate-entity-to-template! conn tenant environment % master-key)
             (keyword (str "migrated/" %)))
          entity-ids)))

;; =============================================================================
;; Backwards Compatibility
;; =============================================================================

(defn get-compatible-params
  "Get RAG parameters in the format expected by existing rag-pipeline.

   This allows the new template system to produce params compatible
   with the existing digdir.rag.core/rag-pipeline function.

   Args:
     template-instance - Instantiated template
     collections - Collection name map

   Returns: Params map compatible with rag-pipeline"
  [template-instance collections]
  (let [params (:parameters template-instance {})]
    {:docsCollectionName (:docs-collection collections)
     :chunksCollectionName (:chunks-collection collections)
     :phrasesCollectionName (:phrases-collection collections)
     :rerankTopkChunks (get-in params [:builtin/rerank :top-k] 40)
     :rerankMaxChunkLength (get-in params [:builtin/rerank :max-chunk-length] 1000)
     :rerankMaxLength (get-in params [:builtin/rerank :max-total-length] 10000)
     :contextTopkChunks (get-in params [:builtin/rerank :context-top-k] 10)
     :contextMaxChunkLength (get-in params [:builtin/rerank :context-max-chunk-length] 1000)
     :maxContextLength (get-in params [:builtin/synthesis :max-context] 8000)
     :promptRagQueryRelax (get-in params [:builtin/query-planner :prompt])
     :promptRagGenerate (get-in params [:builtin/synthesis :generation-prompt])}))

(defn execute-legacy-compatible
  "Execute a template in a way that returns results compatible with legacy format.

   Args:
     template-id - Template to execute
     query - User query
     collections - Collection names
     opts - Execution options

   Returns: Result in legacy rag-pipeline format"
  [template-id query collections opts]
  (let [inputs {:user-query query
                :docs-collection (:docs-collection collections)
                :chunks-collection (:chunks-collection collections)
                :phrases-collection (:phrases-collection collections)}
        graph-result ((requiring-resolve 'digdir.pipeline.skills.api/run-template)
                      template-id inputs opts)
        outputs (:outputs graph-result)]
    ;; Convert to legacy format
    {:translated_answer (:response outputs)
     :chunks (vec (:chunks outputs))
     :search_queries (:search-phrases outputs)
     :rag_success (boolean (:response outputs))}))

(comment
  ;; Migrate an entity to a template
  (migrate-entity-to-template!
    (config-db/get-conn)
    "ka"
    "prod"
    "my-bot"
    "master-key")

  ;; Migrate all entities for a tenant
  (migrate-all-entities!
    (config-db/get-conn)
    "ka"
    "prod"
    "master-key")

  ;; Execute with legacy compatibility
  (execute-legacy-compatible
    :builtin/simple-qa
    "How do I file taxes?"
    {:docs-collection "ka_docs"
     :chunks-collection "ka_chunks"
     :phrases-collection "ka_phrases"}
    {:tenant "ka" :environment "prod"}))
