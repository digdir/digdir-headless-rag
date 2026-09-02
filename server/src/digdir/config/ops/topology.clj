(ns digdir.config.ops.topology
  (:require [digdir.agents.db :as agents-db]
            [digdir.config.db :as config-db]
            [digdir.config.ops.bootstrap :as ops-bootstrap]
            [digdir.config.ops.materialization :as ops-materialization]
            [digdir.pipeline.core :as pipeline-core]
            [digdir.pipeline.materialization :as materialization]))

(def ^:private deployment-target-tenant-config-keys
  ["default"])

(def ^:private deployment-target-tenants
  {:digdir "digdir"
   :public-sector-knowledge "public-sector-knowledge"})

(def ^:private deployment-target-datasets
  {:public-docs {:dataset-id "public-docs"
                 :name "Public Docs"}
   :kudos {:dataset-id "kudos"
           :name "Kudos"}})

(def ^:private deployment-target-pipelines
  {:public-docs [{:pipeline-id "altinn-docs"
                  :name "Altinn Docs"}
                 {:pipeline-id "digdir-docs"
                  :name "Digdir Docs"
                  :property-overrides {:website-sitemap-url "https://docs.digdir.no/sitemap.xml"
                                       :website-base-url "https://docs.digdir.no"}}]
   :kudos [{:pipeline-id "kudos"
            :name "Kudos"}]})

(def ^:private deployment-placeholder-agents
  [{:id "interactive-doc-improve"
    :name "Interactive Documentation Improvement (placeholder)"
    :description "Placeholder — interactive documentation-improvement agent that interviews a subject-matter expert. Runs on the generic RAG graph until specialized interview skills exist."
    :instructions
    (str
     "You are an interactive documentation-improvement agent. Drive a structured interview with the user "
     "to identify unclear, missing, outdated, or inconsistent documentation, then summarize the proposed "
     "improvements with explicit assumptions and open questions.\n\n"
     "Current limitations: this is a placeholder definition before specialized skills exist. Stay grounded "
     "in retrieved evidence, ask focused follow-up questions, and avoid claiming that changes are final.\n\n"
     "Future skill candidates: interview planning, documentation gap detection, change proposal drafting, "
     "cross-page consistency checks, and stakeholder handoff summaries.")
    :default-skill-graph "builtin/agent-rag-graph-bundled"
    :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"
                           "builtin/agent-rag-graph-faithful"]
    :guardrails {:citations-required true
                 :interaction-style :interview}
    :enabled? true}

   ;; "qualtiy" is misspelled and MUST NOT be casually corrected (#52). This id
   ;; is a FOREIGN KEY, not a label: it is written into :conversation/agent-id on
   ;; every persisted playground conversation, keys this agent's runtime-tree
   ;; config nodes in both the digdir and public-sector-knowledge trees, is
   ;; matched by string below in placeholder-agent-dataset-scopes, and is
   ;; embedded in the MCP tool name exposed to clients. Renaming here alone
   ;; orphans all of that.
   ;;
   ;; Correcting it needs a migration that rewrites the existing references, and
   ;; that is not worth doing for a spelling nobody is harmed by - this is a
   ;; placeholder agent whose id changes anyway when the real plain-language
   ;; skills replace it. That is the moment to fix the spelling.
   {:id "plain-language-qualtiy-check"
    :name "Plain Language Quality Check (placeholder)"
    :description "Placeholder — semi-autonomous plain-language review agent for readability and clarity. Runs on the generic RAG graph until dedicated plain-language skills exist."
    :instructions
    (str
     "You are a plain-language review agent. Assess whether documentation is understandable, concrete, "
     "well structured, and easy to act on. Highlight readability issues, explain why they matter, and "
     "propose clearer rewrites when useful.\n\n"
     "Current limitations: this is a placeholder definition before dedicated plain-language skills exist. "
     "Use retrieved evidence, be explicit about uncertainty, and prefer actionable review notes over broad opinions.\n\n"
     "Future skill candidates: plain-language scoring, readability diagnostics, rewrite suggestion generation, "
     "terminology simplification, and audience-fit review.")
    :default-skill-graph "builtin/agent-rag-graph-bundled"
    :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"
                           "builtin/agent-rag-graph-faithful"]
    :guardrails {:citations-required true
                 :answer-style :review}
    :enabled? true}

   {:id "cross-sector-researcher"
    :name "Cross-Sector Researcher (placeholder)"
    :description "Placeholder — interactive research agent that compiles answers across public-sector organizations. Runs on the generic RAG graph until dedicated tabulation and batch-research skills exist."
    :instructions
    (str
     "You are an interactive research agent that helps compile comparable answers across public-sector "
     "organizations. Break the work into explicit research questions, gather grounded evidence for each "
     "organization, and organize the findings into a column-oriented structure that can be exported later.\n\n"
     "Current limitations: this is a placeholder definition before dedicated tabulation and batch-research "
     "skills exist. Keep results grounded, track missing evidence clearly, and separate confirmed findings "
     "from assumptions.\n\n"
     "Future skill candidates: multi-organization query planning, structured tabulation, result completeness "
     "checks, and spreadsheet-ready output generation.")
    :default-skill-graph "builtin/agent-rag-graph-bundled"
    :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"
                           "builtin/agent-rag-graph-faithful"]
    :guardrails {:citations-required true
                 :answer-style :research}
    :enabled? true}])

(defn- decode-resolved-root-values
  [results definitions-by-path master-key]
  (reduce-kv
   (fn [acc path {:keys [value]}]
     (if-let [value-entity value]
       (let [definition (get definitions-by-path path)]
         (assoc acc path
                (config-db/decode-value (:config.value/raw value-entity)
                                        (:config-def/value-type definition)
                                        (:config-def/encrypted? definition)
                                        master-key)))
       acc))
   {}
   results))

(defn- resolve-root-values
  [db root tenant node-id master-key]
  (let [definitions (config-db/get-definitions-by-root db root)
        paths (mapv :config-def/path definitions)
        definitions-by-path (into {} (map (juxt :config-def/path identity)) definitions)
        {:keys [results]} (config-db/resolve-node-values-batch db root tenant node-id paths)]
    (decode-resolved-root-values results definitions-by-path master-key)))

(defn- resolved-platform-values
  [db {:keys [tenant tenant-config-key]} master-key]
  (let [selected-node (config-db/resolve-platform-node! db
                                                       {:tenant tenant
                                                        :tenant-config-key tenant-config-key})]
    (resolve-root-values db :platform tenant (:config.node/id selected-node) master-key)))

(defn- resolved-runtime-values
  [db {:keys [tenant tenant-config-key agent-id dataset-id]} master-key]
  (let [selected-node (config-db/resolve-runtime-node! db
                                                       {:tenant tenant
                                                        :tenant-config-key tenant-config-key
                                                        :agent-id agent-id
                                                        :dataset-id dataset-id})]
    (resolve-root-values db :runtime tenant (:config.node/id selected-node) master-key)))

(defn- resolve-source-pipeline-properties
  [db {:keys [tenant tenant-config-key pipeline-id]} master-key]
  (or (some-> (config-db/get-dataset db tenant tenant-config-key pipeline-id master-key)
              (dissoc :id :dataset-id :dataset-node-id)
              (#(cond-> %
                   (nil? (:source-type %))
                   (as-> props
                         (if-let [inferred (pipeline-core/infer-source-type props)]
                           (assoc props :source-type inferred)
                           props)))))
      (throw (ex-info "Source pipeline config not found"
                      {:tenant tenant
                       :tenant-config-key tenant-config-key
                       :pipeline-id pipeline-id}))))

(defn- resolve-first-pipeline-source
  [db sources master-key]
  (or (some (fn [source]
              (try
                {:source source
                 :properties (resolve-source-pipeline-properties db source master-key)}
                (catch clojure.lang.ExceptionInfo _
                  nil)))
            sources)
      (throw (ex-info "No pipeline source config found"
                      {:sources sources}))))

(defn- resolve-first-platform-source
  [db source-tenants tenant-config-key master-key]
  (or (some (fn [tenant]
              (try
                (let [values (resolved-platform-values db
                                                       {:tenant tenant
                                                        :tenant-config-key tenant-config-key}
                                                       master-key)]
                  (when (seq values)
                    {:tenant tenant
                     :values values}))
                (catch clojure.lang.ExceptionInfo _
                  nil)))
            source-tenants)
      {:tenant nil
       :values {}}))

(defn- source-pipeline-name
  [properties default-name]
  (or (:name properties)
      default-name))

(defn- upsert-dataset-record!
  [conn {:keys [dataset-id name description enabled?]
         :or {enabled? true}}]
  (if (config-db/get-dataset-record @conn dataset-id)
    (config-db/update-dataset! conn (cond-> {:dataset-id dataset-id
                                             :name name
                                             :enabled? enabled?}
                                      (some? description) (assoc :description description)))
    (config-db/create-dataset! conn {:dataset-id dataset-id
                                     :name name
                                     :description description
                                     :enabled? enabled?})))

(defn- upsert-dataset-pipeline-record!
  [conn {:keys [pipeline-id dataset-id enabled?]
         :or {enabled? true}}]
  (if (config-db/get-dataset-pipeline @conn pipeline-id)
    (config-db/update-dataset-pipeline! conn (cond-> {:pipeline-id pipeline-id
                                                      :dataset-id dataset-id
                                                      :enabled? enabled?}))
    (config-db/create-dataset-pipeline! conn {:pipeline-id pipeline-id
                                              :dataset-id dataset-id
                                              :enabled? enabled?})))

(def ^:private dataset-shared-materialization-keys
  #{:chunk-strategy
    :chunk-minimum-length
    :chunk-maximum-length
    :search-phrases-model
    :search-phrases-fallback
    :search-phrases-prompt
    :collection-prefix
    :docs-collection
    :chunks-collection
    :phrases-collection
    :parallelism-documents
    :parallelism-store
    :max-document-failures})

(defn- select-dataset-shared-materialization-values
  [values]
  (select-keys values dataset-shared-materialization-keys))

(defn- select-pipeline-leaf-values
  [values]
  (apply dissoc values dataset-shared-materialization-keys))

(defn- pipeline-bootstrap-values
  [base-values {:keys [name property-overrides]}]
  (-> (select-pipeline-leaf-values base-values)
      (merge (or property-overrides {}))
      (assoc :name name)))

(defn- runtime-dataset-scopes
  [tenant dataset-ids]
  (->> dataset-ids
       distinct
       sort
       (mapv (fn [dataset-id]
               {:tenant tenant
                :dataset-config-key dataset-id}))))

(defn- bootstrap-shared-platform-tree!
  [conn {:keys [tenant tenant-name source-tenants tenant-config-keys master-key created-by]
         :or {created-by "deployment-polish"}}]
  (let [primary-tenant-config-key (or (first tenant-config-keys) "default")
        source (resolve-first-platform-source @conn source-tenants primary-tenant-config-key master-key)
        source-tenant (:tenant source)
        source-values (:values source)]
    (assoc
     ;; Values go on the BASE node, which is the tenant root (#275).
     ;;
     ;; This used to seed a "shared" leaf and hang it under the base. Config
     ;; inheritance runs CHILD -> PARENT, and the runtime enters at the tenant
     ;; root - `tenant-root-node!` is literally a lookup of tenant-config-key
     ;; "default" - so values on a leaf below the root were unreachable. Every
     ;; one of the 109 `:services` read sites resolves that way and none passes
     ;; a key, so nothing could ever see them.
     ;;
     ;; The other three platform bootstraps - setup/workflow.clj,
     ;; setup/config.clj and e2e/seed.clj - already do exactly this. This call
     ;; was the outlier, not the convention.
     (ops-bootstrap/bootstrap-config-tree! conn
                                           {:root :platform
                                            :tenant tenant
                                            :tenant-name tenant-name
                                            :created-by created-by
                                            :base-node-id (str "platform/" tenant "/default")
                                            :single-node? true
                                            :base-values source-values
                                            :master-key master-key})
     :source-tenant source-tenant)))

(defn- bootstrap-shared-runtime-tree!
  [conn {:keys [tenant tenant-name runtime-values master-key created-by]
         :or {created-by "deployment-polish"}}]
  (ops-bootstrap/bootstrap-config-tree! conn
                                        {:root :runtime
                                         :tenant tenant
                                         :tenant-name tenant-name
                                         :created-by created-by
                                         :base-node-id (str "runtime/" tenant "/default")
                                         :leaf-node-id (str "runtime/" tenant "/default-runtime")
                                         :leaf-label "Default Runtime"
                                         :leaf-tenant-config-key "default-runtime"
                                         :leaf-values runtime-values
                                         :master-key master-key}))

(defn- bootstrap-shared-dataset-tree!
  [conn {:keys [tenant tenant-name dataset-id dataset-name pipeline-specs shared-values tenant-config-keys created-by master-key]
         :or {created-by "deployment-polish"}}]
  (let [tenant-config-key-values (vec tenant-config-keys)
        _ (when-not (seq tenant-config-key-values)
            (throw (ex-info "At least one dataset tenant-config-key is required"
                            {:tenant tenant
                             :dataset-id dataset-id})))
        base-node-id (str "dataset/" tenant "/" dataset-id "/default")]
    (upsert-dataset-record! conn {:dataset-id dataset-id
                                  :name dataset-name
                                  :enabled? true})
    (doseq [{:keys [pipeline-id]} pipeline-specs]
      (upsert-dataset-pipeline-record! conn {:pipeline-id pipeline-id
                                             :dataset-id dataset-id
                                             :enabled? true}))
    {:dataset (ops-bootstrap/bootstrap-config-tree! conn
                                                    {:root :dataset
                                                     :tenant tenant
                                                     :tenant-name tenant-name
                                                     :created-by created-by
                                                     :base-node-id base-node-id
                                                     :leaf-node-id (str "dataset/" tenant "/" dataset-id "/shared-materialization")
                                                     :leaf-label (str dataset-name " Shared Materialization")
                                                     :leaf-tenant-config-key (str dataset-id "-shared-materialization")
                                                     :base-values shared-values
                                                     :leaf-values {}
                                                     :master-key master-key})
     :pipelines
     (vec
      (mapcat (fn [{:keys [pipeline-id name values]}]
                (mapv (fn [tenant-config-key]
                        (ops-bootstrap/bootstrap-dataset-tree! conn
                                                               {:tenant tenant
                                                                :tenant-name tenant-name
                                                                :created-by created-by
                                                                :dataset-id dataset-id
                                                                :pipeline-id pipeline-id
                                                                :base-node-id base-node-id
                                                                :materialization-node-id (str "dataset/" tenant "/" dataset-id "/" tenant-config-key "/" pipeline-id "/materialization")
                                                                :materialization-label (str name " Materialization")
                                                                :materialization-tenant-config-key (config-db/default-dataset-tenant-config-key tenant-config-key pipeline-id)
                                                                :base-values shared-values
                                                                :dataset-values values
                                                                :master-key master-key}))
                      tenant-config-key-values))
              pipeline-specs))}))

(defn bootstrap-deployment-target-topology!
  "Bootstrap the target deployment topology for the post-V2 deployment polish phase."
  [conn {:keys [master-key created-by tenant-config-keys
                public-docs-source kudos-source
                digdir-platform-source-tenants public-sector-platform-source-tenants
                public-sector-runtime-source]
         :or {created-by "deployment-polish"
              tenant-config-keys deployment-target-tenant-config-keys
              public-docs-source {:tenant "altinn"
                                  :tenant-config-key "dev"
                                  :pipeline-id "assistant"}
              kudos-source {:tenant "ka"
                            :tenant-config-key "dev"
                            :pipeline-id "kudos"}
              digdir-platform-source-tenants ["altinn-docs" "altinn"]
              public-sector-platform-source-tenants ["ka"]
              public-sector-runtime-source {:tenant "ka"
                                            :tenant-config-key "default"
                                            :agent-id "builtin/agent-rag-agent"
                                            :dataset-id "kudos"}}}]
  (let [db @conn
        primary-tenant-config-key (or (first tenant-config-keys) "default")
        digdir-tenant (:digdir deployment-target-tenants)
        public-sector-tenant (:public-sector-knowledge deployment-target-tenants)
        builtin-agents (agents-db/seed-builtin-agents! conn)
        all-agent-ids (vec (concat (map :id builtin-agents)
                                   (map :id deployment-placeholder-agents)))
        public-docs-resolution (resolve-first-pipeline-source db
                                                              [public-docs-source
                                                               {:tenant digdir-tenant
                                                                :tenant-config-key primary-tenant-config-key
                                                                :pipeline-id "altinn-docs"}]
                                                              master-key)
        kudos-resolution (resolve-first-pipeline-source db
                                                        [kudos-source
                                                         {:tenant public-sector-tenant
                                                          :tenant-config-key primary-tenant-config-key
                                                          :pipeline-id "kudos"}]
                                                        master-key)
        public-docs-props (:properties public-docs-resolution)
        kudos-props (:properties kudos-resolution)
        public-docs-source-type (or (:source-type public-docs-props)
                                    (throw (ex-info "Unable to infer public-docs source type"
                                                    {:source (:source public-docs-resolution)
                                                     :properties (keys public-docs-props)})))
        kudos-source-type (or (:source-type kudos-props)
                              (throw (ex-info "Unable to infer kudos source type"
                                              {:source (:source kudos-resolution)
                                               :properties (keys kudos-props)})))
        public-docs-values (-> (merge (materialization/target-materialization-contract
                                       {:tenant "digdir"
                                        :dataset-id "public-docs"
                                        :source-type public-docs-source-type})
                                      public-docs-props)
                               (dissoc :name)
                               (assoc :website-sitemap-url (or (:website-sitemap-url public-docs-props)
                                                               "https://docs.altinn.studio/sitemap.xml"))
                               (assoc :website-base-url (or (:website-base-url public-docs-props)
                                                            "https://docs.altinn.studio")))
        kudos-values (-> (merge (materialization/target-materialization-contract
                                 {:tenant "public-sector-knowledge"
                                  :dataset-id "kudos"
                                  :source-type kudos-source-type})
                                kudos-props)
                         (dissoc :name))
        digdir-runtime-values {}
        public-sector-runtime-values (try
                                       (resolved-runtime-values db public-sector-runtime-source master-key)
                                       (catch clojure.lang.ExceptionInfo _
                                         (try
                                           (resolved-runtime-values db
                                                                    {:tenant public-sector-tenant
                                                                     :tenant-config-key "default"
                                                                     :agent-id "builtin/agent-rag-agent"
                                                                     :dataset-id "kudos"}
                                                                    master-key)
                                           (catch clojure.lang.ExceptionInfo _
                                             {}))))
        public-docs-dataset (:public-docs deployment-target-datasets)
        kudos-dataset (:kudos deployment-target-datasets)
        public-docs-pipeline-specs (mapv #(-> %
                                             (assoc :source-type public-docs-source-type)
                                             (assoc :values (pipeline-bootstrap-values public-docs-values %)))
                                         (:public-docs deployment-target-pipelines))
        kudos-pipeline-specs (mapv #(-> %
                                       (assoc :name (source-pipeline-name kudos-props "Kudos"))
                                       (assoc :source-type kudos-source-type)
                                       (assoc :values (pipeline-bootstrap-values kudos-values
                                                                                 {:name (source-pipeline-name kudos-props "Kudos")})))
                                   (:kudos deployment-target-pipelines))
        digdir-platform (bootstrap-shared-platform-tree! conn
                                                         {:tenant digdir-tenant
                                                          :tenant-name "Digdir"
                                                          :source-tenants (vec (concat digdir-platform-source-tenants
                                                                                       [digdir-tenant]))
                                                          :tenant-config-keys tenant-config-keys
                                                          :created-by created-by
                                                          :master-key master-key})
        public-sector-platform (bootstrap-shared-platform-tree! conn
                                                                {:tenant public-sector-tenant
                                                                 :tenant-name "Public Sector Knowledge"
                                                                 :source-tenants (vec (concat public-sector-platform-source-tenants
                                                                                              [public-sector-tenant]))
                                                                 :tenant-config-keys tenant-config-keys
                                                                 :created-by created-by
                                                                 :master-key master-key})
        digdir-dataset (bootstrap-shared-dataset-tree! conn
                                                       {:tenant digdir-tenant
                                                        :tenant-name "Digdir"
                                                        :dataset-id (:dataset-id public-docs-dataset)
                                                        :dataset-name (:name public-docs-dataset)
                                                        :pipeline-specs public-docs-pipeline-specs
                                                        :shared-values (select-dataset-shared-materialization-values public-docs-values)
                                                        :tenant-config-keys tenant-config-keys
                                                        :created-by created-by
                                                        :master-key master-key})
        public-sector-dataset-tree (bootstrap-shared-dataset-tree! conn
                                                                   {:tenant public-sector-tenant
                                                                    :tenant-name "Public Sector Knowledge"
                                                                    :dataset-id (:dataset-id kudos-dataset)
                                                                    :dataset-name (:name kudos-dataset)
                                                                    :pipeline-specs kudos-pipeline-specs
                                                                    :shared-values (select-dataset-shared-materialization-values kudos-values)
                                                                    :tenant-config-keys tenant-config-keys
                                                                    :created-by created-by
                                                                    :master-key master-key})
        materialization-defaults (ops-materialization/seed-target-materialization-defaults!
                                  conn
                                  {:master-key master-key
                                   :tenant-config-key (or (first tenant-config-keys) "default")})
        placeholder-agent-dataset-scopes (fn [agent-id]
                                         (cond
                                           (#{"interactive-doc-improve"
                                              "plain-language-qualtiy-check"} agent-id)
                                           (runtime-dataset-scopes digdir-tenant
                                                                   [(:dataset-id public-docs-dataset)])

                                           (= "cross-sector-researcher" agent-id)
                                           (runtime-dataset-scopes public-sector-tenant
                                                                   [(:dataset-id kudos-dataset)])

                                           :else
                                           []))
        placeholder-agents (mapv #(agents-db/upsert-agent!
                                   conn
                                   (assoc % :allowed-dataset-scopes
                                          (placeholder-agent-dataset-scopes (:id %))))
                                 deployment-placeholder-agents)
        digdir-runtime (bootstrap-shared-runtime-tree! conn
                                                       {:tenant digdir-tenant
                                                        :tenant-name "Digdir"
                                                        :dataset-ids [(:dataset-id public-docs-dataset)]
                                                        :agent-ids (->> (concat (map :id builtin-agents)
                                                                                (map :id placeholder-agents))
                                                                        distinct
                                                                        vec)
                                                        :runtime-values digdir-runtime-values
                                                        :created-by created-by
                                                        :master-key master-key})
        public-sector-runtime (bootstrap-shared-runtime-tree! conn
                                                              {:tenant public-sector-tenant
                                                               :tenant-name "Public Sector Knowledge"
                                                               :dataset-ids [(:dataset-id kudos-dataset)]
                                                               :agent-ids (->> (concat (map :id builtin-agents)
                                                                                       (map :id placeholder-agents))
                                                                               distinct
                                                                               vec)
                                                               :runtime-values public-sector-runtime-values
                                                               :created-by created-by
                                                               :master-key master-key})]
    {:tenants [{:tenant-id digdir-tenant
                :name "Digdir"}
               {:tenant-id public-sector-tenant
                :name "Public Sector Knowledge"}]
     :platform {:digdir digdir-platform
                :public-sector-knowledge public-sector-platform}
     :datasets {:digdir digdir-dataset
                :public-sector-knowledge public-sector-dataset-tree}
     :materialization-defaults materialization-defaults
     :runtime {:digdir digdir-runtime
               :public-sector-knowledge public-sector-runtime}
     :agents {:builtin (mapv :id builtin-agents)
              :placeholder (mapv :id placeholder-agents)}
     :sources {:public-docs (:source public-docs-resolution)
               :kudos (:source kudos-resolution)}
     :target-pipelines {:digdir (mapv :pipeline-id public-docs-pipeline-specs)
                        :public-sector-knowledge (mapv :pipeline-id kudos-pipeline-specs)}
     :target-datasets {:digdir (:dataset-id public-docs-dataset)
                       :public-sector-knowledge (:dataset-id kudos-dataset)}
     :all-agent-ids all-agent-ids}))
