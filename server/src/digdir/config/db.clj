(ns digdir.config.db
  "Database operations for configuration management.

   Live configuration is root-aware and node-based:
   - shared config definitions
   - tenant-local config trees (nodes, bindings, node values)
   - durable dataset and dataset-pipeline records

   Legacy tuple-scoped config is retired from the live database model. The only
   remaining tuple helpers are historical export parsers such as
   `make-config-id` and `parse-config-id`."
  (:require [datahike.api :as d]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [digdir.config.core :as core]
            [digdir.config.schema :as schema]
            [digdir.config.structure :as structure]
            [digdir.config.crypto :as crypto]))

;; =============================================================================
;; Connection Management
;; =============================================================================

(defonce ^:private !config-conn (atom nil))

(declare get-tenant
         list-tenants)

(defn set-conn!
  "Set or override the Datahike connection for config operations.
   In normal runtime the config role shares the main app connection."
  [conn]
  (reset! !config-conn conn))

(defn get-conn
  "Get the Datahike connection for config operations.
   Returns the shared main app connection unless an explicit override has been set."
  []
  (or @!config-conn
      (do (require 'digdir.data.db)
          ((resolve 'digdir.data.db/get-conn)))))

(defn ensure-schema!
  "Ensure config schema is transacted to the database.
   Safe to call multiple times - Datahike ignores duplicate schema."
  [conn]
  (try
    (d/transact conn {:tx-data schema/config-migration-schema})
    (catch Exception e
      ;; Ignore "attribute already exists" errors
      (when-not (re-find #"already exists" (str (.getMessage e)))
        (throw e)))))

;; =============================================================================
;; One-shot data migrations
;; =============================================================================

(defn- migration-applied?
  [db migration-id]
  (some? (d/q '[:find ?e .
                :in $ ?id
                :where [?e :digdir.migration/id ?id]]
              db migration-id)))

(defn- mark-migration-applied!
  [conn migration-id]
  (d/transact conn
              {:tx-data [{:digdir.migration/id migration-id
                          :digdir.migration/applied-at (System/currentTimeMillis)}]}))

(defn- retract-ownership-where-indexed!
  "Retract :config-def/ownership from every entity whose value is visible via
   the attribute index. May miss entities whose value was written before the
   attribute had a declared schema."
  [conn]
  (let [db @conn
        eids (d/q '[:find [?e ...]
                    :where [?e :config-def/ownership]]
                  db)]
    (when (seq eids)
      (d/transact conn
                  {:tx-data (mapv (fn [eid] [:db/retract eid :config-def/ownership])
                                  eids)}))
    (count eids)))

(defn- retract-ownership-for-all-config-defs!
  "Retract :config-def/ownership from EVERY config-def entity, whether or not
   the value is visible to the attribute index. Handles definitions whose
   ownership was written under the old inferred schema and therefore isn't
   reachable via `[?e :config-def/ownership]`."
  [conn]
  (let [db @conn
        eids (d/q '[:find [?e ...]
                    :where [?e :config-def/path]]
                  db)]
    (when (seq eids)
      (d/transact conn
                  {:tx-data (mapv (fn [eid] [:db/retract eid :config-def/ownership])
                                  eids)}))
    (count eids)))

(def ^:private env-migrated-paths
  "Config paths that have been moved to environment variables and are no longer
   read through the config DB. Keeping definitions for these in the DB is
   misleading — they appear in the operator console but writes are ignored."
  #{"services.auth.admin-user-emails"
    "services.auth.approved-domains"
    "services.auth.cookie-domain"
    "services.auth.jwt-cookie-max-age"
    "services.auth.jwt-secret"
    "services.auth.jwt-token-expiry-hours"
    "services.auth.secure-cookies?"
    "services.auth.session-max-age"
    "services.auth.use-db"
    "services.rate-limiting.trust-x-forwarded-for"})

(def ^:private rerank-mode-split-spec
  "Drives the 2026-04-27-rerank-mode-split migration. Each entry maps an old
   path to its two new mode-namespaced replacements with their seed values."
  [{:old "skills.rerank.max-chunk-length"
    ;; RAG seed raised 400 -> 4000 (#463) — see setup/workflow.clj. This spec
    ;; mints the __global__ default for any deployment that has not yet run the
    ;; split, so leaving it at 400 would land migrating deployments on the value
    ;; #463 declares wrong while fresh setups get 4000.
    :rag {:path "skills.rerank.rag.max-chunk-length" :value 4000}
    :retrieval {:path "skills.rerank.retrieval.max-chunk-length" :value 4000}}
   {:old "skills.rerank.max-total-length"
    :rag {:path "skills.rerank.rag.max-total-length" :value 16000}
    :retrieval {:path "skills.rerank.retrieval.max-total-length" :value 32000}}
   {:old "skills.rerank.max-context-length"
    :rag {:path "skills.rerank.rag.max-context-length" :value 8000}
    :retrieval {:path "skills.rerank.retrieval.max-context-length" :value 32000}}
   {:old "skills.rerank.context.top-k"
    :rag {:path "skills.rerank.rag.context.top-k" :value 10}
    :retrieval {:path "skills.rerank.retrieval.context.top-k" :value 10}}
   {:old "skills.rerank.context.max-chunk-length"
    :rag {:path "skills.rerank.rag.context.max-chunk-length" :value 2000}
    :retrieval {:path "skills.rerank.retrieval.context.max-chunk-length" :value 4000}}])

(defn- rerank-mode-split-migration!
  "Run the rerank-mode split. Pre-flight: refuses to run if any tenant has an
   active value at one of the old paths (we promised behavior preservation,
   and the rename has no path to merge two values into one). Body: writes 10
   global defaults via ops-global/set-global-value!, then retracts the 5 old
   definitions."
  [conn]
  (let [db @conn
        old-paths (set (map :old rerank-mode-split-spec))
        ;; Pre-flight — confirm no tenant values point at old defs.
        old-def-eids (->> (d/datoms db :aevt :config-def/path)
                          (filter #(contains? old-paths (:v %)))
                          (map :e)
                          distinct
                          set)
        active-values (->> (d/datoms db :aevt :config.value/definition)
                           (filter #(contains? old-def-eids (:v %)))
                           ;; Only count active (non-deleted) values
                           (filter (fn [d]
                                     (nil? (:v (first (d/datoms db :eavt (:e d) :config.value/deleted-at))))))
                           count)]
    (when (pos? active-values)
      (throw (ex-info "Rerank-mode split refused: existing tenant values found at old paths"
                      {:active-values active-values
                       :old-paths (vec old-paths)
                       :remediation "manually move the values to the new mode-namespaced paths first, or unpin them"})))
    ;; Write 10 global defaults via ops-global.
    (let [set-global! (do (require 'digdir.config.ops.global)
                          (resolve 'digdir.config.ops.global/set-global-value!))
          master-key (core/get-master-key)
          changelog "rerank-mode-split: seed initial global defaults for new mode-namespaced paths"]
      (doseq [{:keys [rag retrieval]} rerank-mode-split-spec]
        (set-global! conn {:path (:path rag)
                           :value (:value rag)
                           :master-key master-key
                           :changelog changelog
                           :created-by "post-definition-migration"})
        (set-global! conn {:path (:path retrieval)
                           :value (:value retrieval)
                           :master-key master-key
                           :changelog changelog
                           :created-by "post-definition-migration"})))
    ;; Retract the 5 old definition entities.
    (when (seq old-def-eids)
      (d/transact conn
                  {:tx-data (mapv (fn [eid] [:db/retractEntity eid]) old-def-eids)}))
    {:definitions-retracted (count old-def-eids)
     :globals-written (* 2 (count rerank-mode-split-spec))}))

(defn- retract-skills-retrieval-enabled-migration!
  "Retract the skills.retrieval.enabled config-def. The path was registered as
   a runtime knob but never had a consumer — the retrieval skill doesn't read
   :enabled from its parameters, and the path was dropped from
   skill-property-to-path during the 2026-05 audit. Pre-flight refuses if any
   tenant has set a value (operator should review before retraction)."
  [conn]
  (let [db @conn
        path "skills.retrieval.enabled"
        def-eids (->> (d/datoms db :aevt :config-def/path)
                      (filter #(= path (:v %)))
                      (map :e)
                      distinct
                      set)
        active-values (->> (d/datoms db :aevt :config.value/definition)
                           (filter #(contains? def-eids (:v %)))
                           (filter (fn [d]
                                     (nil? (:v (first (d/datoms db :eavt (:e d) :config.value/deleted-at))))))
                           count)]
    (when (pos? active-values)
      (throw (ex-info "skills.retrieval.enabled retraction refused: tenant values found"
                      {:path path
                       :active-values active-values
                       :remediation "delete the tenant values via the admin UI first, then re-run boot"})))
    (when (seq def-eids)
      (d/transact conn
                  {:tx-data (mapv (fn [eid] [:db/retractEntity eid]) def-eids)}))
    {:definitions-retracted (count def-eids)
     :path path}))

(defn- remove-env-migrated-paths!
  "Retract config-def and config-value entities for paths that have been moved
   to environment variables. Audit entries are preserved — only the dangling
   :audit/config-def ref is dropped (audit/config-path remains as denormalized
   history)."
  [conn]
  (let [db @conn
        def-eids (->> (d/datoms db :aevt :config-def/path)
                      (filter #(contains? env-migrated-paths (:v %)))
                      (map :e)
                      distinct
                      vec)
        def-eid-set (set def-eids)
        value-eids (->> (d/datoms db :aevt :config.value/definition)
                        (filter #(contains? def-eid-set (:v %)))
                        (map :e)
                        distinct
                        vec)]
    (when (seq value-eids)
      (d/transact conn
                  {:tx-data (mapv (fn [eid] [:db/retractEntity eid]) value-eids)}))
    (when (seq def-eids)
      (d/transact conn
                  {:tx-data (mapv (fn [eid] [:db/retractEntity eid]) def-eids)}))
    {:definitions-removed (count def-eids)
     :values-removed (count value-eids)}))

(defn- dedupe-config-defs!
  "Retract legacy duplicate config-def entities. Historically definitions were
   written before :config-def/path had :db.unique/identity enforced, so some
   DBs accumulated two entities per path. Keep the entity that carries the
   current :config-def/ownership declaration (falling back to the highest eid
   when none do), retract the rest via :db/retractEntity, and relink any
   dangling :config.value/definition refs to the survivor.

   Idempotent: when no duplicates exist, this is a no-op."
  [conn]
  (let [db @conn
        paths->eids (->> (d/datoms db :aevt :config-def/path)
                         (reduce (fn [acc d]
                                   (update acc (:v d) (fnil conj []) (:e d)))
                                 {}))
        duplicates (into {} (filter (fn [[_ es]] (> (count es) 1))) paths->eids)
        canonical-for (fn [eids]
                        (let [with-ownership (filter #(seq (d/datoms db :eavt % :config-def/ownership))
                                                     eids)]
                          (if (seq with-ownership)
                            (first with-ownership)
                            (apply max eids))))
        stale-by-canonical (into {}
                                 (for [[_ eids] duplicates
                                       :let [canonical (canonical-for eids)]]
                                   [canonical (vec (remove #{canonical} eids))]))
        all-stale-eids (into #{} (mapcat val) stale-by-canonical)
        ;; Repoint any lingering :config.value/definition refs onto the canonical eid.
        ;; Datahike's :vaet index isn't exposed, so scan all value->def edges via :aevt.
        stale->canonical (into {}
                               (for [[canonical stale-eids] stale-by-canonical
                                     stale-eid stale-eids]
                                 [stale-eid canonical]))
        value-repoints (->> (d/datoms db :aevt :config.value/definition)
                            (filter #(contains? stale->canonical (:v %)))
                            (mapv (fn [datom]
                                    [:db/add (:e datom) :config.value/definition
                                     (get stale->canonical (:v datom))])))
        retract-tx (mapv (fn [eid] [:db/retractEntity eid]) all-stale-eids)]
    (when (seq value-repoints)
      (d/transact conn {:tx-data (vec value-repoints)}))
    (when (seq retract-tx)
      (d/transact conn {:tx-data retract-tx}))
    {:duplicate-paths (count duplicates)
     :stale-entities-retracted (count all-stale-eids)
     :values-repointed (count value-repoints)}))

(def ^:private one-shot-migrations
  [{:id "2026-04-23-ownership-reset"
    :description "Clear :config-def/ownership values that were written before
                  the attribute had a declared schema so the next pass of
                  ensure-all-config-definitions! can re-apply them cleanly.
                  (Index-visible entities only.)"
    :run! retract-ownership-where-indexed!}
   {:id "2026-04-23-ownership-reset-v2"
    :description "Broader retract of :config-def/ownership across every
                  config-def entity — catches values that were written under
                  the old inferred schema and are invisible to attribute-index
                  queries. Followed by re-apply via ensure-all-config-definitions!."
    :run! retract-ownership-for-all-config-defs!}
   {:id "2026-04-23-dedupe-config-defs"
    :description "Retract legacy duplicate config-def entities introduced
                  before :config-def/path had :db.unique/identity enforced.
                  Keeps the entity that holds the correct ownership value and
                  relinks any config-value refs to the survivor."
    :run! dedupe-config-defs!}
   {:id "2026-04-24-remove-env-migrated-paths"
    :description "Drop config-def and config-value entities for services.auth.*
                  and services.rate-limiting.trust-x-forwarded-for — these
                  settings are now read from environment variables and should
                  no longer appear in the operator console."
    :run! remove-env-migrated-paths!}])

(defn apply-one-shot-migrations!
  "Run any pending one-shot data migrations. Each migration's result is marked
   via a :digdir.migration/id entity so subsequent boots skip applied work."
  [conn]
  (doseq [{:keys [id run!] :as migration} one-shot-migrations]
    (when-not (migration-applied? @conn id)
      (log/info "Applying one-shot config migration"
                {:id id :description (:description migration)})
      (run! conn)
      (mark-migration-applied! conn id))))

;; =============================================================================
;; Post-definition migrations
;;
;; Some migrations need definitions to exist before they can run — e.g. writing
;; a global value via `set-global-value!` requires the def to already declare
;; :ownership :inherit. These migrations execute AFTER
;; `ensure-config-definitions-on-boot!` in the init-db boot chain.
;; =============================================================================

(def ^:private post-definition-migrations
  [{:id "2026-04-27-rerank-mode-split"
    :description "Split skills.rerank.{max-chunk-length,max-total-length,
                  max-context-length,context.top-k,context.max-chunk-length}
                  into rag/retrieval mode-namespaced variants. Retracts the 5
                  old definitions (no tenant values exist) and seeds 10 global
                  defaults so each mode has its own value."
    :run! rerank-mode-split-migration!}
   {:id "2026-05-08-retract-skills-retrieval-enabled"
    :description "Retract the orphaned skills.retrieval.enabled config-def.
                  Never consumed by any skill; dropped from skill-property-to-path
                  during the 2026-05 builder coverage audit. Pre-flight refuses
                  if tenant values exist."
    :run! retract-skills-retrieval-enabled-migration!}])

(defn apply-post-definition-migrations!
  "Run migrations whose body needs config definitions to already exist —
   typically because they call `ops-global/set-global-value!` which asserts
   :ownership :inherit on the target definition. Idempotent via the same
   :digdir.migration/id marker mechanism as `apply-one-shot-migrations!`."
  [conn]
  (doseq [{:keys [id run!] :as migration} post-definition-migrations]
    (when-not (migration-applied? @conn id)
      (log/info "Applying post-definition config migration"
                {:id id :description (:description migration)})
      (run! conn)
      (mark-migration-applied! conn id))))

;; =============================================================================
;; Definition CRUD
;; =============================================================================

(declare ensure-valid-root! ensure-present-string!
         resolve-platform-node! get-definitions-by-root
         get-dataset
         parse-dataset-node-id
         resolve-node-values-batch)

(def ^:private legacy-dataset-pipeline-projection-definitions
  {"pipeline.ui.name" {:root :dataset
                       :value-type :string
                       :description "Dataset materialization property: display name"
                       :category :pipelines
                       :service :other
                       :sensitivity :internal
                       :function :settings}
   "pipeline.source.type" {:root :dataset
                           :value-type :edn
                           :description "Dataset materialization source type"
                           :category :pipelines
                           :service :storage
                           :sensitivity :internal
                           :function :settings}})

(defn get-definition
  "Get a config definition by path."
  [db path]
  (when path
    (d/q '[:find (pull ?e [*]) .
           :in $ ?path
           :where [?e :config-def/path ?path]]
         db path)))

(defn get-all-definitions
  "List all config definitions."
  [db]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :where [?e :config-def/path]]
            db)
       (sort-by :config-def/path)
       vec))

(defn get-definitions-by-root
  "List config definitions for one root."
  [db root]
  (ensure-valid-root! root)
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :in $ ?root
              :where
              [?e :config-def/path]
              [?e :config-def/root ?root]]
            db root)
       (sort-by :config-def/path)
       vec))

(defn- build-definition-tx-data
  [{:keys [path root value-type encrypted? description category service sensitivity function multiline? ownership]
    :as _definition}
   created-at]
  (ensure-present-string! path :path)
  (ensure-valid-root! root)
  (cond-> {:config-def/path path
           :config-def/root root
           :config-def/value-type value-type
           :config-def/encrypted? (boolean encrypted?)
           :config-def/multiline? (boolean multiline?)
           :config-def/created-at created-at}
    (some? description) (assoc :config-def/description description)
    (some? category) (assoc :config-def/category category)
    (some? service) (assoc :config-def/service service)
    (some? sensitivity) (assoc :config-def/sensitivity sensitivity)
    (some? function) (assoc :config-def/function function)
    (some? ownership) (assoc :config-def/ownership ownership)))

(defn- definition-matches?
  "True when an existing definition entity already has every field the desired tx-data would set."
  [existing tx-data]
  (and (some? existing)
       (every? (fn [[k v]]
                 (or (= k :config-def/created-at)
                     (= v (get existing k))))
               tx-data)))

(defn- assert-no-duplicate-defs!
  "Refuse to upsert when the DB already has more than one entity for a given
   path. Historical DBs accumulated duplicates when :config-def/path was
   written before its :db.unique/identity schema was registered; see
   migration id `2026-04-23-dedupe-config-defs`. Failing loud here surfaces
   any remaining corruption instead of silently writing to a randomly-selected
   duplicate."
  [db path]
  (let [eids (d/q '[:find [?e ...]
                    :in $ ?p
                    :where [?e :config-def/path ?p]]
                  db path)]
    (when (> (count eids) 1)
      (throw (ex-info (str "Duplicate config-def entities for path "
                           (pr-str path)
                           " — run the 2026-04-23-dedupe-config-defs migration")
                      {:path path
                       :eids (vec eids)
                       :migration "2026-04-23-dedupe-config-defs"})))))

(defn upsert-definition!
  "Create or update a config definition. Skips the write when the stored row already matches.

   created-at priority: input wins (authoritative for import flows), else
   preserve the row that's already in the DB, else use the current clock."
  [conn definition]
  (let [db @conn
        path (:path definition)
        _ (assert-no-duplicate-defs! db path)
        existing (get-definition db path)
        created-at (or (:created-at definition)
                       (:config-def/created-at existing)
                       (System/currentTimeMillis))
        tx-data (build-definition-tx-data definition created-at)]
    (if (definition-matches? existing tx-data)
      existing
      (do (d/transact conn {:tx-data [tx-data]})
          (get-definition @conn path)))))

(defn ensure-definition-root!
  "Ensure an existing definition is rooted as expected.

   This is a small migration/backfill helper for code paths that may encounter an
   older definition row before it has been rewritten with explicit root metadata."
  [conn path root]
  (ensure-present-string! path :path)
  (ensure-valid-root! root)
  (when-let [definition (get-definition @conn path)]
    (when-let [existing-root (:config-def/root definition)]
      (when (not= root existing-root)
        (throw (ex-info "Definition root mismatch"
                        {:path path
                         :expected-root root
                         :existing-root existing-root}))))
    (when-not (= root (:config-def/root definition))
      (d/transact conn {:tx-data [{:db/id [:config-def/path path]
                                   :config-def/root root}]}))
    (get-definition @conn path)))

(defn upsert-definitions-batch!
  "Create or update many config definitions in a single transaction."
  [conn definitions]
  (let [db @conn
        _ (doseq [d definitions]
            (assert-no-duplicate-defs! db (:path d)))
        tx-data (mapv (fn [definition]
                        (let [existing (get-definition db (:path definition))
                              ;; Input wins so dump-import overrides whatever
                              ;; init-config-db! pre-seeded with `now`.
                              created-at (or (:created-at definition)
                                             (:config-def/created-at existing)
                                             (System/currentTimeMillis))]
                          (build-definition-tx-data definition created-at)))
                      definitions)]
    (when (seq tx-data)
      (d/transact conn {:tx-data tx-data}))
    (mapv #(get-definition @conn (:path %)) definitions)))

;; =============================================================================
;; Legacy Export Helpers
;; =============================================================================

(defn make-config-id
  "Generate the legacy tuple-scoped config identifier used only for historical export migration.

   Format: tenant:env:client:skill-graph:pipeline:path
   Legacy 4-segment inputs are still parsed by `parse-config-id`."
  ([tenant tenant-config-key pipeline path]
   (make-config-id tenant tenant-config-key nil nil pipeline path))
  ([tenant tenant-config-key client skill-graph pipeline path]
   (str (or tenant "_") ":"
        (or tenant-config-key "_") ":"
        (or client "_") ":"
        (or skill-graph "_") ":"
        (or pipeline "_") ":"
        path)))

(defn parse-config-id
  "Parse a legacy tuple-scoped config ID for historical export migration."
  [config-id]
  (let [parts (str/split config-id #":")]
    (case (count parts)
      4
      (let [[tenant env pipeline path] parts]
        {:tenant (when (not= tenant "_") tenant)
         :tenant-config-key (when (not= env "_") env)
         :client nil
         :skill-graph nil
         :pipeline (when (not= pipeline "_") pipeline)
         :path path})
      (let [[tenant env client skill-graph pipeline path] (str/split config-id #":" 6)]
        {:tenant (when (not= tenant "_") tenant)
         :tenant-config-key (when (not= env "_") env)
         :client (when (not= client "_") client)
         :skill-graph (when (not= skill-graph "_") skill-graph)
         :pipeline (when (not= pipeline "_") pipeline)
         :path path}))))

;; =============================================================================
;; Value Encoding
;; =============================================================================

(defn decode-value
  "Decode a stored config value based on its type.

   Args:
     raw-value - String value from database
     value-type - :string, :boolean, :number, :edn
     encrypted? - Whether value is encrypted
     master-key - Master key for decryption (required if encrypted)"
  [raw-value value-type encrypted? master-key]
  (let [decrypted (if encrypted?
                    (crypto/decrypt raw-value master-key)
                    raw-value)]
    (case value-type
      :string decrypted
      :boolean (= decrypted "true")
      :number (edn/read-string decrypted)
      :edn (edn/read-string decrypted)
      decrypted)))

(defn encode-value
  "Encode a value for storage based on its type.

   Args:
     value - The value to encode
     value-type - :string, :boolean, :number, :edn
     encrypted? - Whether to encrypt the value
     master-key - Master key for encryption (required if encrypted)"
  [value value-type encrypted? master-key]
  (let [string-val (case value-type
                     :string (str value)
                     :boolean (str value)
                     :number (str value)
                     :edn (pr-str value)
                     (str value))]
    (if encrypted?
      (crypto/encrypt string-val master-key)
      string-val)))

(defn load-resolved-config
  "Load rooted Platform config for a tenant/node into a nested map.

   This is the remaining bulk-load helper used by setup and infrastructure code.
   It resolves Platform V2 node values only. Legacy tuple-scoped config is no
   longer read from the live database."
  ([db tenant tenant-config-key master-key]
   (load-resolved-config db tenant tenant-config-key nil nil nil master-key))
  ([db tenant tenant-config-key _client _skill-graph _pipeline master-key]
   (let [selected-node (resolve-platform-node! db {:tenant tenant
                                                   :tenant-config-key tenant-config-key})
         definitions (get-definitions-by-root db :platform)
         paths (mapv :config-def/path definitions)
         defs-by-path (into {} (map (juxt :config-def/path identity) definitions))
         {:keys [results]} (resolve-node-values-batch db
                                                      :platform
                                                      tenant
                                                      (:config.node/id selected-node)
                                                      paths)]
     (reduce (fn [acc path]
               (if-let [value (get-in results [path :value])]
                 (let [definition (get defs-by-path path)
                       decoded (decode-value (:config.value/raw value)
                                             (:config-def/value-type definition)
                                             (:config-def/encrypted? definition)
                                             master-key)
                       path-keys (mapv keyword (str/split path #"\."))]
                   (assoc-in acc path-keys decoded))
                 acc))
             {}
             paths))))

(defn count-legacy-config-values
  "Count remaining tuple-scoped config value entities still present in the database."
  [db]
  (count (d/q '[:find [?e ...]
                :where [?e :config/id]]
              db)))

(defn purge-legacy-config-values!
  "Delete all remaining tuple-scoped config value entities from the database.

   Returns {:deleted n}."
  [conn]
  (let [eids (d/q '[:find [?e ...]
                    :where [?e :config/id]]
                  @conn)]
    (when (seq eids)
      (d/transact conn {:tx-data (mapv (fn [eid] [:db/retractEntity eid]) eids)}))
    {:deleted (count eids)}))

;; =============================================================================
;; Config Tree CRUD (v2)
;; =============================================================================

;; DERIVED, NOT DECLARED. This was a hand-written set that happened to match
;; `digdir.config.api-keys/valid-config-roots`; nothing made them agree. Both now
;; read the same structure file. Do not inline the set back here.
(def ^:private config-roots structure/config-roots)

(def ^:private max-config-node-depth 20)

(def ^:private dataset-pull
  '[*])

(def ^:private dataset-pipeline-pull
  '[* {:dataset.pipeline/dataset [:dataset/id :dataset/name :dataset/enabled?]}])

(defn- ensure-valid-root!
  [root]
  (when-not (contains? config-roots root)
    (throw (ex-info "Invalid config root"
                    {:root root
                     :allowed-roots (sort config-roots)}))))

(defn- ensure-present-string!
  [value k]
  (when (str/blank? value)
    (throw (ex-info "Expected non-blank string"
                    {:field k
                     :value value}))))

(defn- distinct-by
  [f coll]
  (vals
   (reduce (fn [acc x]
             (assoc acc (f x) x))
           (array-map)
           coll)))

(defn config-node-eid
  "Get the EID of a config node by its unique node-id."
  [db node-id]
  (d/q '[:find ?e .
         :in $ ?node-id
         :where [?e :config.node/id ?node-id]]
       db node-id))

(defn- config-node-eid-by-tenant-config-key
  [db tenant root tenant-config-key]
  (d/q '[:find ?e .
         :in $ ?tenant ?root ?tenant-config-key
         :where
         [?e :config.node/tenant ?tenant]
         [?e :config.node/root ?root]
         [?e :config.node/tenant-config-key ?tenant-config-key]]
       db tenant root tenant-config-key))

(defn- dataset-eid
  [db dataset-id]
  (d/q '[:find ?e .
         :in $ ?dataset-id
         :where [?e :dataset/id ?dataset-id]]
       db dataset-id))

(defn- dataset-pipeline-eid
  [db pipeline-id]
  (d/q '[:find ?e .
         :in $ ?pipeline-id
         :where [?e :dataset.pipeline/id ?pipeline-id]]
       db pipeline-id))

(defn- config-node-value-entity
  [db value-id]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?value-id
         :where [?e :config.value/id ?value-id]]
       db value-id))

(defn- config-node-ancestor-ids
  [db node-eid]
  (loop [current-eid node-eid
         ancestor-ids []]
    (if-let [parent-id (d/q '[:find ?parent-id .
                              :in $ ?node
                              :where
                              [?node :config.node/parent ?parent]
                              [?parent :config.node/id ?parent-id]]
                            db current-eid)]
      (let [next-ancestor-ids (conj ancestor-ids parent-id)]
        (when (> (count next-ancestor-ids) max-config-node-depth)
          (throw (ex-info "Config node tree exceeds maximum supported depth"
                          {:max-depth max-config-node-depth
                           :ancestor-ids next-ancestor-ids})))
        (recur (config-node-eid db parent-id)
               next-ancestor-ids))
      ancestor-ids)))

(declare get-config-node
         dataset-materialization-node-id)

;; =============================================================================
;; Fixed Root Chain
;; =============================================================================

(def ^:private fixed-root-chain-ids
  {:platform ["_platform_global"]
   :runtime  ["_runtime_global"]
   :dataset  ["_dataset_global"]})

(defn- get-fixed-root-chain
  "Get the system-managed nodes that sit above all tenant trees for a root."
  [db root]
  (keep #(get-config-node db %) (get fixed-root-chain-ids root)))

(defn- prefetch-ancestor-chain
  "Prefetch the full ancestor chain for a node as a vector of node entities,
    starting from the given node and walking up to the root.
    Each entry is a pulled node entity with :config.node/id, :config.node/enabled?, etc.

    The chain follows:
    requested node -> tenant ancestors -> tenant tree root -> fixed root chain nodes"
  [db selected-node]
  (let [root (:config.node/root selected-node)
        tenant-chain (loop [current-node selected-node
                            chain []]
                       (if-not current-node
                         chain
                         (let [chain' (conj chain current-node)]
                           (when (> (count chain') max-config-node-depth)
                             (throw (ex-info "Config node tree exceeds maximum supported depth"
                                             {:max-depth max-config-node-depth})))
                           (recur (when-let [parent-id (get-in current-node [:config.node/parent :config.node/id])]
                                    (get-config-node db parent-id))
                                  chain'))))
        fixed-chain (get-fixed-root-chain db root)]
    (vec (concat tenant-chain fixed-chain))))

(defn- ensure-node-enabled!
  [node]
  (when-not (:config.node/enabled? node)
    (throw (ex-info "Config node is disabled"
                    {:node-id (:config.node/id node)
                     :root (:config.node/root node)
                     :tenant (:config.node/tenant node)}))))

(defn- ensure-node-root-and-tenant!
  [node root tenant]
  (when (not= root (:config.node/root node))
    (throw (ex-info "Config node root mismatch"
                    {:requested-root root
                     :node-root (:config.node/root node)
                     :node-id (:config.node/id node)})))
  (when (and (not (:config.node/system-managed? node))
             (not= tenant (:config.node/tenant node)))
    (throw (ex-info "Config node tenant mismatch"
                    {:requested-tenant tenant
                     :node-tenant (:config.node/tenant node)
                     :node-id (:config.node/id node)}))))
(defn- require-config-node!
  [db root tenant node-id]
  (let [node (or (get-config-node db node-id)
                 (throw (ex-info "Config node not found" {:node-id node-id})))]
    (ensure-node-root-and-tenant! node root tenant)
    node))

(defn- validate-config-node-parent!
  [db node parent]
  (when (= (:config.node/id node) (:config.node/id parent))
    (throw (ex-info "Config node cannot be its own parent"
                    {:node-id (:config.node/id node)})))
  (when (not= (:config.node/root node) (:config.node/root parent))
    (throw (ex-info "Parent node must share the same root"
                    {:node-id (:config.node/id node)
                     :node-root (:config.node/root node)
                     :parent-id (:config.node/id parent)
                     :parent-root (:config.node/root parent)})))
  (when (not= (:config.node/tenant node) (:config.node/tenant parent))
    (throw (ex-info "Parent node must share the same tenant"
                    {:node-id (:config.node/id node)
                     :node-tenant (:config.node/tenant node)
                     :parent-id (:config.node/id parent)
                     :parent-tenant (:config.node/tenant parent)})))
  (let [parent-ancestor-ids (config-node-ancestor-ids db (config-node-eid db (:config.node/id parent)))]
    (when (> (inc (count parent-ancestor-ids)) max-config-node-depth)
      (throw (ex-info "Config node tree exceeds maximum supported depth"
                      {:max-depth max-config-node-depth
                       :node-id (:config.node/id node)
                       :parent-id (:config.node/id parent)})))
    (when (some #{(:config.node/id node)} parent-ancestor-ids)
      (throw (ex-info "Config node parent would introduce a cycle"
                      {:node-id (:config.node/id node)
                       :parent-id (:config.node/id parent)})))))

(defn make-node-value-id
  "Generate a stable config value ID for the config-tree model."
  [root tenant node-id path]
  (str (name root) ":" tenant ":" node-id ":" path))

(defn get-config-node
  "Get a config node by stable node ID."
  [db node-id]
  (when-let [node-eid (config-node-eid db node-id)]
    (let [node (d/pull db '[:config.node/id
                            :config.node/root
                            :config.node/tenant
                            :config.node/label
                            :config.node/tenant-config-key
                            :config.node/system-managed?
                            :config.node/enabled?
                            :config.node/created-at
                            :config.node/updated-at]
                       node-eid)
          parent-summary (first
                          (d/q '[:find ?parent-id ?parent-root ?parent-tenant
                                 :in $ ?node
                                 :where
                                 [?node :config.node/parent ?parent]
                                 [?parent :config.node/id ?parent-id]
                                 [?parent :config.node/root ?parent-root]
                                 [?parent :config.node/tenant ?parent-tenant]]
                               db node-eid))]
      (if parent-summary
        (let [[parent-id parent-root parent-tenant] parent-summary]
          (assoc node :config.node/parent {:config.node/id parent-id
                                           :config.node/root parent-root
                                           :config.node/tenant parent-tenant}))
        node))))

(defn get-config-node-by-tenant-config-key
  "Get a config node by stable tenant-local tenant-config-key."
  [db tenant root tenant-config-key]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! tenant-config-key :tenant-config-key)
  (ensure-valid-root! root)
  (when-let [node-eid (config-node-eid-by-tenant-config-key db tenant root tenant-config-key)]
    (get-config-node db (:config.node/id (d/pull db [:config.node/id] node-eid)))))

(defn list-config-nodes
  "List config nodes for a tenant and root."
  [db tenant root]
  (ensure-present-string! tenant :tenant)
  (ensure-valid-root! root)
  (let [nodes (->> (d/q '[:find [(pull ?e [:config.node/id
                                           :config.node/root
                                           :config.node/tenant
                                           :config.node/label
                                           :config.node/tenant-config-key
                                           :config.node/system-managed?
                                           :config.node/enabled?
                                           :config.node/created-at
                                           :config.node/updated-at]) ...]
                       :in $ ?tenant ?root
                       :where
                       [?e :config.node/tenant ?tenant]
                       [?e :config.node/root ?root]]
                     db tenant root)
                   (distinct-by :config.node/id))
        parents-by-child-id (->> (d/q '[:find ?child-id ?parent-id ?parent-root ?parent-tenant
                                        :in $ ?tenant ?root
                                        :where
                                        [?child :config.node/tenant ?tenant]
                                        [?child :config.node/root ?root]
                                        [?child :config.node/id ?child-id]
                                        [?child :config.node/parent ?parent]
                                        [?parent :config.node/id ?parent-id]
                                        [?parent :config.node/root ?parent-root]
                                        [?parent :config.node/tenant ?parent-tenant]]
                                      db tenant root)
                            (reduce (fn [acc [child-id parent-id parent-root parent-tenant]]
                                      (assoc acc child-id {:config.node/id parent-id
                                                           :config.node/root parent-root
                                                           :config.node/tenant parent-tenant}))
                                    {}))]
    (->> nodes
         (mapv (fn [node]
                 (if-let [parent (get parents-by-child-id (:config.node/id node))]
                   (assoc node :config.node/parent parent)
                   node)))
         (sort-by :config.node/id)
         vec)))

(defn create-config-node!
  "Create a config node in a tenant-local tree.

   :created-at / :updated-at — optional; preserved on initial create when
   round-tripping a dump. Default to the current clock."
  [conn {:keys [root tenant node-id label tenant-config-key parent-id enabled? system-managed?
                created-at updated-at]
         :or {enabled? true
              system-managed? false}}]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! node-id :node-id)
  (ensure-present-string! label :label)
  (ensure-present-string! tenant-config-key :tenant-config-key)
  (ensure-valid-root! root)
  (let [db @conn
        _ (when (get-config-node db node-id)
            (throw (ex-info "Config node already exists" {:node-id node-id})))
        _ (when (config-node-eid-by-tenant-config-key db tenant root tenant-config-key)
            (throw (ex-info "Config node tenant-config-key already exists for tenant/root"
                            {:tenant tenant
                             :root root
                             :tenant-config-key tenant-config-key})))
        now (System/currentTimeMillis)
        parent (when parent-id
                 (or (get-config-node db parent-id)
                     (throw (ex-info "Config node parent not found" {:parent-id parent-id}))))
        node {:config.node/id node-id
              :config.node/root root
              :config.node/tenant tenant
              :config.node/label label
              :config.node/tenant-config-key tenant-config-key
              :config.node/system-managed? (boolean system-managed?)
              :config.node/enabled? enabled?
              :config.node/created-at (or created-at now)
              :config.node/updated-at (or updated-at now)}
        node (cond-> node
               parent-id (assoc :config.node/parent [:config.node/id parent-id]))]
    (when parent
      (validate-config-node-parent! db node parent))
    (d/transact conn {:tx-data [node]})
    (get-config-node @conn node-id)))

(defn update-config-node!
  "Update mutable attributes on an existing config node.

   :created-at / :updated-at — optional; if provided (e.g. by dump-import),
   override the existing row. UI callers omit them and the existing row's
   created-at is preserved while updated-at bumps to now."
  [conn {:keys [node-id label tenant-config-key enabled? system-managed?
                created-at updated-at] :as opts}]
  (ensure-present-string! node-id :node-id)
  (let [db @conn
        existing (or (get-config-node db node-id)
                     (throw (ex-info "Config node not found" {:node-id node-id})))
        _ (when (and (contains? opts :tenant-config-key)
                     tenant-config-key)
            (when-let [existing-tenant-config-key-node (get-config-node-by-tenant-config-key db
                                                                   (:config.node/tenant existing)
                                                                   (:config.node/root existing)
                                                                   tenant-config-key)]
              (when (not= node-id (:config.node/id existing-tenant-config-key-node))
                (throw (ex-info "Config node tenant-config-key already exists for tenant/root"
                                {:tenant (:config.node/tenant existing)
                                 :root (:config.node/root existing)
                                 :tenant-config-key tenant-config-key
                                 :node-id node-id
                                 :existing-node-id (:config.node/id existing-tenant-config-key-node)})))))
        tx-data (cond-> {:db/id [:config.node/id node-id]
                         :config.node/updated-at (or updated-at (System/currentTimeMillis))}
                  created-at (assoc :config.node/created-at created-at)
                  (contains? opts :label) (assoc :config.node/label label)
                  (contains? opts :tenant-config-key) (assoc :config.node/tenant-config-key tenant-config-key)
                  (contains? opts :system-managed?) (assoc :config.node/system-managed? (boolean system-managed?))
                  (contains? opts :enabled?) (assoc :config.node/enabled? enabled?))]
    (d/transact conn {:tx-data [tx-data]})
    (get-config-node @conn (:config.node/id existing))))

(defn set-config-node-parent!
  "Attach a node to a parent within the same root and tenant."
  [conn {:keys [node-id parent-id]}]
  (ensure-present-string! node-id :node-id)
  (ensure-present-string! parent-id :parent-id)
  (let [db @conn
        node (or (get-config-node db node-id)
                 (throw (ex-info "Config node not found" {:node-id node-id})))
        parent (or (get-config-node db parent-id)
                   (throw (ex-info "Config node parent not found" {:parent-id parent-id})))]
    (validate-config-node-parent! db node parent)
    (d/transact conn {:tx-data [{:db/id [:config.node/id node-id]
                                 :config.node/parent [:config.node/id parent-id]
                                 :config.node/updated-at (System/currentTimeMillis)}]})
    (get-config-node @conn node-id)))

(defn clear-config-node-parent!
  "Remove the parent from a config node, making it a root node."
  [conn {:keys [node-id]}]
  (ensure-present-string! node-id :node-id)
  (let [db @conn
        node (or (get-config-node db node-id)
                 (throw (ex-info "Config node not found" {:node-id node-id})))]
    (d/transact conn {:tx-data [[:db/retract [:config.node/id node-id] :config.node/parent]
                                {:db/id [:config.node/id node-id]
                                 :config.node/updated-at (System/currentTimeMillis)}]})
    (get-config-node @conn (:config.node/id node))))

(defn delete-config-node!
  "Delete a config node after confirming it has no children.
   Associated node-scoped values are deleted in the same transaction."
  [conn {:keys [root tenant node-id]}]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! node-id :node-id)
  (ensure-valid-root! root)
  (let [db @conn
        _node (require-config-node! db root tenant node-id)
        node-eid (config-node-eid db node-id)
        child-ids (d/q '[:find [?child-id ...]
                         :in $ ?node
                         :where
                         [?child :config.node/parent ?node]
                         [?child :config.node/id ?child-id]]
                       db node-eid)]
    (when (seq child-ids)
      (throw (ex-info "Cannot delete config node with children"
                      {:node-id node-id
                       :child-ids (vec (sort child-ids))})))
    (let [value-eids (d/q '[:find [?e ...]
                            :in $ ?node
                            :where [?e :config.value/node ?node]]
                          db node-eid)
          tx-data (vec (concat (map (fn [eid] [:db/retractEntity eid]) value-eids)
                               [[:db/retractEntity node-eid]]))]
      (when (seq tx-data)
        (d/transact conn {:tx-data tx-data}))
      true)))

(defn- tenant-root-node-error-data
  [db tenant root]
  (let [nodes (list-config-nodes db tenant root)]
    {:kind :tenant-root-missing
     :tenant tenant
     :root root
     :required-tenant-config-key "default"
     :available-node-count (count nodes)
     :available-root-nodes (mapv (fn [node]
                                   (select-keys node [:config.node/id
                                                      :config.node/label
                                                      :config.node/tenant-config-key
                                                      :config.node/enabled?
                                                      :config.node/system-managed?]))
                                 nodes)}))

(defn- tenant-root-node-error
  [db tenant root]
  (let [{:keys [required-tenant-config-key available-node-count available-root-nodes]
         :as error-data} (tenant-root-node-error-data db tenant root)
        root-label (pr-str root)]
    (ex-info
     (if (pos? available-node-count)
       (format "Canonical tenant root node not found for tenant '%s' and root %s. Expected tenant-config-key '%s'. Available root nodes: %s"
               tenant
               root-label
               required-tenant-config-key
               (pr-str available-root-nodes))
       (format "Canonical tenant root node not found for tenant '%s' and root %s. Expected tenant-config-key '%s', but no nodes exist for this tenant/root."
               tenant
               root-label
               required-tenant-config-key))
     error-data)))

(defn- tenant-root-node!
  [db tenant root]
  (or (get-config-node-by-tenant-config-key db tenant root "default")
      (throw (tenant-root-node-error db tenant root))))

(defn- require-explicit-selected-node
  [db {:keys [tenant root node-id tenant-config-key]}]
  (cond
    node-id (get-config-node db node-id)
    tenant-config-key (get-config-node-by-tenant-config-key db tenant root tenant-config-key)
    :else nil))

(defn resolve-config-node!
  "Unified resolver for any config root.
   
   Inputs:
   - db: database value
   - root: a config root (see digdir.config.structure)
   - tenant: string
   - params: map with optional :node-id, :tenant-config-key, :agent-id, :dataset-id, :pipeline-id
   
   Enforces:
   - Explicit tenant-config-key/ID selection (or fallback to tenant root if allowed)
   - system-managed? check for explicit requests"
  [db root tenant {:keys [node-id tenant-config-key _agent-id _dataset-id _pipeline-id] :as _params}]
  (ensure-present-string! tenant :tenant)
  (ensure-valid-root! root)
  (let [node (cond
               (or node-id tenant-config-key)
               (or (require-explicit-selected-node db {:tenant tenant
                                                       :root root
                                                       :node-id node-id
                                                       :tenant-config-key tenant-config-key})
                   (throw (ex-info (str (name root) " config node not found")
                                   {:tenant tenant
                                    :root root
                                    :tenant-config-key tenant-config-key})))
               
               :else
               (tenant-root-node! db tenant root))]
    
    (ensure-node-root-and-tenant! node root tenant)
    (ensure-node-enabled! node)
    
    (when (and (or node-id tenant-config-key) (:config.node/system-managed? node))
       (throw (ex-info "Explicit requests must target a tenant node, not a system-managed node"
                       {:tenant tenant
                        :root root
                        :node-id (:config.node/id node)
                        :tenant-config-key (:config.node/tenant-config-key node)})))
    
    node))

(defn resolve-platform-node!
  "Resolve a platform node from explicit node tenant-config-key/ID, or fall back to the tenant root."
  [db {:keys [tenant node-id tenant-config-key]}]
  (resolve-config-node! db :platform tenant {:node-id node-id
                                             :tenant-config-key tenant-config-key}))

(defn resolve-runtime-node!
  "Resolve a runtime node from an explicit tenant node tenant-config-key/ID.

   Compatibility is validated on the tenant root node."
  [db {:keys [tenant _node-id _tenant-config-key _agent-id _dataset-id] :as params}]
  (resolve-config-node! db :runtime tenant params))

(defn resolve-dataset-node!
  "Resolve a dataset node from an explicit tenant node tenant-config-key/ID.

   Compatibility is validated on the tenant root node."
  [db {:keys [tenant node-id tenant-config-key dataset-config-key dataset-id pipeline-id] :as params}]
  (let [resolved-node-id (cond
                           node-id
                           node-id

                           (and dataset-id pipeline-id (or dataset-config-key tenant-config-key))
                           (dataset-materialization-node-id tenant
                                                            (or dataset-config-key tenant-config-key)
                                                            dataset-id
                                                            pipeline-id)

                           :else
                           nil)
        resolved-tenant-config-key (if resolved-node-id
                                     nil
                                     tenant-config-key)]
    (resolve-config-node! db :dataset tenant
                          (cond-> params
                            resolved-node-id (assoc :node-id resolved-node-id)
                            true (assoc :tenant-config-key resolved-tenant-config-key)))))


(defn get-dataset-record
  "Get a dataset by stable dataset ID."
  [db dataset-id]
  (when-let [dataset-eid (dataset-eid db dataset-id)]
    (d/pull db dataset-pull dataset-eid)))

(defn list-dataset-records
  "List all durable dataset records."
  [db]
  (->> (d/q '[:find [(pull ?e [*]) ...]
              :where [?e :dataset/id]]
            db)
       (sort-by :dataset/id)
       vec))

(defn generate-dataset-id
  "Generate a new opaque globally unique dataset ID."
  []
  (str "ds_" (random-uuid)))

(defn create-dataset!
  "Create a durable dataset record.

   :created-at / :updated-at — optional; preserved on initial create when
   round-tripping a dump."
  [conn {:keys [dataset-id name description enabled? created-at updated-at]
         :or {enabled? true}}]
  (ensure-present-string! name :name)
  (let [dataset-id (or dataset-id
                       (generate-dataset-id))
        db @conn
        _ (when (get-dataset-record db dataset-id)
            (throw (ex-info "Dataset already exists" {:dataset-id dataset-id})))
        now (System/currentTimeMillis)]
    (d/transact conn {:tx-data [(cond-> {:dataset/id dataset-id
                                         :dataset/name name
                                         :dataset/enabled? enabled?
                                         :dataset/created-at (or created-at now)
                                         :dataset/updated-at (or updated-at now)}
                                  description (assoc :dataset/description description))]})
    (get-dataset-record @conn dataset-id)))

(defn update-dataset!
  "Update mutable dataset attributes.

   :created-at / :updated-at — optional; when provided (e.g. dump-import),
   override the existing row."
  [conn {:keys [dataset-id name description enabled? created-at updated-at] :as opts}]
  (ensure-present-string! dataset-id :dataset-id)
  (let [existing (or (get-dataset-record @conn dataset-id)
                     (throw (ex-info "Dataset not found" {:dataset-id dataset-id})))
        tx-data (cond-> {:db/id [:dataset/id dataset-id]
                         :dataset/updated-at (or updated-at (System/currentTimeMillis))}
                  created-at (assoc :dataset/created-at created-at)
                  (contains? opts :name) (assoc :dataset/name name)
                  (contains? opts :description) (assoc :dataset/description description)
                  (contains? opts :enabled?) (assoc :dataset/enabled? enabled?))]
    (d/transact conn {:tx-data [tx-data]})
    (get-dataset-record @conn (:dataset/id existing))))

(defn get-dataset-pipeline
  "Get a pipeline by stable pipeline ID."
  [db pipeline-id]
  (when-let [pipeline-eid (dataset-pipeline-eid db pipeline-id)]
    (d/pull db dataset-pipeline-pull pipeline-eid)))

(defn- pipeline-projection-drift
  [pipeline-record resolved-config]
  (let [drift (cond-> {}
                (and (contains? resolved-config :name)
                     (some? (:dataset.pipeline/name pipeline-record))
                     (not= (:name resolved-config) (:dataset.pipeline/name pipeline-record)))
                (assoc :name {:record (:dataset.pipeline/name pipeline-record)
                              :config (:name resolved-config)})

                (and (contains? resolved-config :source-type)
                     (some? (:dataset.pipeline/source-type pipeline-record))
                     (not= (:source-type resolved-config) (:dataset.pipeline/source-type pipeline-record)))
                (assoc :source-type {:record (:dataset.pipeline/source-type pipeline-record)
                                     :config (:source-type resolved-config)}))]
    (when (seq drift)
      drift)))

(defn- infer-pipeline-materialization-context
  ([db pipeline-record]
   (some (fn [tenant]
           (infer-pipeline-materialization-context db pipeline-record tenant))
         (list-tenants db)))
  ([db pipeline-record tenant]
   (some (fn [node]
           (when-let [{:keys [kind pipeline-id tenant-config-key]}
                      (parse-dataset-node-id (:config.node/id node))]
             (when (and (= :materialization kind)
                        (= (:dataset.pipeline/id pipeline-record) pipeline-id))
               {:tenant tenant
                :tenant-config-key tenant-config-key})))
         (filter :config.node/enabled? (list-config-nodes db tenant :dataset)))))

(defn materialization-contexts-by-pipeline-id
  "Single-sweep index of enabled dataset materialization nodes keyed by pipeline-id.

   Returns `{pipeline-id [{:tenant t :tenant-config-key k :node-id id} ...]}`.

   Use this to bound the cost of resolving effective pipeline projection fields
   across many pipelines: callers compute the index once and pass per-pipeline
   contexts into `effective-dataset-pipeline-record` via the `:contexts` opt."
  [db]
  (reduce
   (fn [acc tenant]
     (reduce
      (fn [acc' node]
        (let [{:keys [kind pipeline-id tenant-config-key]}
              (parse-dataset-node-id (:config.node/id node))]
          (if (and (:config.node/enabled? node)
                   (= :materialization kind)
                   pipeline-id)
            (update acc' pipeline-id (fnil conj [])
                    {:tenant tenant
                     :tenant-config-key tenant-config-key
                     :node-id (:config.node/id node)})
            acc')))
      acc
      (list-config-nodes db tenant :dataset)))
   {}
   (list-tenants db)))

(defn- resolve-pipeline-config-for-context
  [db pipeline-id master-key {:keys [tenant tenant-config-key]}]
  (try
    (get-dataset db tenant tenant-config-key pipeline-id master-key)
    (catch clojure.lang.ExceptionInfo e
      (log/debug e "Failed to resolve config-backed pipeline projection"
                 {:pipeline-id pipeline-id
                  :tenant tenant
                  :tenant-config-key tenant-config-key})
      nil)
    (catch Exception e
      (log/debug e "Failed to resolve config-backed pipeline projection"
                 {:pipeline-id pipeline-id
                  :tenant tenant
                  :tenant-config-key tenant-config-key})
      nil)))

(defn- pipeline-contexts-for-resolution
  [db pipeline-record {:keys [tenant tenant-config-key contexts]}]
  (cond
    contexts contexts
    (and tenant tenant-config-key) [{:tenant tenant
                                     :tenant-config-key tenant-config-key}]
    tenant (some-> (infer-pipeline-materialization-context db pipeline-record tenant)
                   vector)
    :else (let [pipeline-id (:dataset.pipeline/id pipeline-record)]
            (get (materialization-contexts-by-pipeline-id db) pipeline-id))))

(defn effective-dataset-pipeline-record
  "Return a pipeline record decorated with effective config-backed projection fields.

   Mutable fields such as name and source-type resolve from dataset-tree config.
   The durable dataset-pipeline record is identity/lifecycle only.

   Opts (all optional):
     :tenant + :tenant-config-key  Resolve against a single named context.
     :tenant only                  Resolve against the first materialization
                                   node in that tenant.
     :contexts [{:tenant :tenant-config-key}]
                                   Use a precomputed context list (preferred for
                                   batched callers — see
                                   `materialization-contexts-by-pipeline-id`).
     :master-key                   Decryption key for any encrypted projection
                                   definitions.

   When the caller has not pinned a tenant and the pipeline has materialization
   nodes in multiple tenants whose values disagree, the field is omitted and a
   `:dataset.pipeline/effective-name-ambiguous?` /
   `:dataset.pipeline/effective-source-type-ambiguous?` flag is set instead.

   The :dataset.pipeline/projection-drift key is attached when the durable record
   still carries legacy :dataset.pipeline/name or :source-type values that disagree
   with the resolved config. After `migrate-legacy-dataset-pipeline-projections!`
   has run this key should never appear; presence indicates a partially migrated
   record or out-of-band tampering."
  ([db pipeline-record]
   (effective-dataset-pipeline-record db pipeline-record nil))
  ([db pipeline-record {:keys [master-key] :as opts}]
   (if-not (and pipeline-record (:dataset.pipeline/enabled? pipeline-record))
     pipeline-record
     (let [pipeline-id (:dataset.pipeline/id pipeline-record)
           contexts (pipeline-contexts-for-resolution db pipeline-record opts)
           resolved-configs (->> contexts
                                 (keep #(resolve-pipeline-config-for-context db pipeline-id master-key %))
                                 vec)
           caller-pinned-tenant? (:tenant opts)
           reduce-projection (fn [k]
                               (let [vs (->> resolved-configs
                                             (map k)
                                             (remove nil?)
                                             distinct
                                             vec)]
                                 (cond
                                   (empty? vs) nil
                                   (= 1 (count vs)) {:value (first vs)}
                                   caller-pinned-tenant? {:value (first vs)}
                                   :else {:ambiguous? true})))
           name-projection (reduce-projection :name)
           source-type-projection (reduce-projection :source-type)
           ;; For drift detection, prefer the first resolved config so legacy
           ;; record values can be compared against something concrete.
           projection-drift (pipeline-projection-drift pipeline-record (first resolved-configs))]
       (when projection-drift
         (log/warn "Dataset pipeline projection drift detected"
                   {:pipeline-id pipeline-id
                    :contexts contexts
                    :drift projection-drift}))
       (when (or (:ambiguous? name-projection)
                 (:ambiguous? source-type-projection))
         (log/warn "Dataset pipeline projection ambiguous across tenants"
                   {:pipeline-id pipeline-id
                    :context-count (count contexts)
                    :name-ambiguous? (:ambiguous? name-projection)
                    :source-type-ambiguous? (:ambiguous? source-type-projection)}))
       (cond-> pipeline-record
         (:value name-projection)
         (assoc :dataset.pipeline/effective-name (:value name-projection))

         (:ambiguous? name-projection)
         (assoc :dataset.pipeline/effective-name-ambiguous? true)

         (some? (:value source-type-projection))
         (assoc :dataset.pipeline/effective-source-type (:value source-type-projection))

         (:ambiguous? source-type-projection)
         (assoc :dataset.pipeline/effective-source-type-ambiguous? true)

         projection-drift (assoc :dataset.pipeline/projection-drift projection-drift))))))

(defn list-dataset-pipelines
  "List dataset pipelines, optionally filtered by owning dataset ID."
  ([db]
   (list-dataset-pipelines db nil))
  ([db dataset-id]
   (->> (if dataset-id
          (d/q '[:find [(pull ?e [* {:dataset.pipeline/dataset [:dataset/id :dataset/name :dataset/enabled?]}]) ...]
                 :in $ ?dataset-id
                 :where
                 [?dataset :dataset/id ?dataset-id]
                 [?e :dataset.pipeline/dataset ?dataset]]
               db dataset-id)
          (d/q '[:find [(pull ?e [* {:dataset.pipeline/dataset [:dataset/id :dataset/name :dataset/enabled?]}]) ...]
                 :where [?e :dataset.pipeline/id]]
               db))
        (sort-by :dataset.pipeline/id)
        vec)))

(defn create-dataset-pipeline!
  "Create a pipeline attached to a durable dataset.

   :created-at / :updated-at — optional; preserved on initial create when
   round-tripping a dump."
  [conn {:keys [pipeline-id dataset-id enabled? created-at updated-at]
         :or {enabled? true}}]
  (ensure-present-string! pipeline-id :pipeline-id)
  (ensure-present-string! dataset-id :dataset-id)
  (let [db @conn
        _ (when (get-dataset-pipeline db pipeline-id)
            (throw (ex-info "Dataset pipeline already exists" {:pipeline-id pipeline-id})))
        _ (or (get-dataset-record db dataset-id)
              (throw (ex-info "Dataset not found" {:dataset-id dataset-id})))
        now (System/currentTimeMillis)]
    (d/transact conn {:tx-data [(cond-> {:dataset.pipeline/id pipeline-id
                                         :dataset.pipeline/dataset [:dataset/id dataset-id]
                                         :dataset.pipeline/enabled? enabled?
                                         :dataset.pipeline/created-at (or created-at now)
                                         :dataset.pipeline/updated-at (or updated-at now)})]})
    (get-dataset-pipeline @conn pipeline-id)))

(defn update-dataset-pipeline!
  "Update mutable pipeline attributes.

   :created-at / :updated-at — optional; when provided (e.g. dump-import),
   override the existing row."
  [conn {:keys [pipeline-id dataset-id enabled? created-at updated-at] :as opts}]
  (ensure-present-string! pipeline-id :pipeline-id)
  (let [existing (or (get-dataset-pipeline @conn pipeline-id)
                     (throw (ex-info "Dataset pipeline not found" {:pipeline-id pipeline-id})))
        _ (when (contains? opts :dataset-id)
            (or (get-dataset-record @conn dataset-id)
                (throw (ex-info "Dataset not found" {:dataset-id dataset-id}))))
        tx-data (cond-> {:db/id [:dataset.pipeline/id pipeline-id]
                         :dataset.pipeline/updated-at (or updated-at (System/currentTimeMillis))}
                  created-at (assoc :dataset.pipeline/created-at created-at)
                  (contains? opts :dataset-id) (assoc :dataset.pipeline/dataset [:dataset/id dataset-id])
                  (contains? opts :enabled?) (assoc :dataset.pipeline/enabled? enabled?))]
    (d/transact conn {:tx-data [tx-data]})
    (get-dataset-pipeline @conn (:dataset.pipeline/id existing))))

(defn get-node-value
  "Get an active node-scoped config value."
  [db root tenant node-id path]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! node-id :node-id)
  (ensure-present-string! path :path)
  (ensure-valid-root! root)
  (let [value-id (make-node-value-id root tenant node-id path)]
    (d/q '[:find (pull ?e [* {:config.value/node [:config.node/id :config.node/root :config.node/tenant
                                                  :config.node/label :config.node/enabled?]}
                             {:config.value/definition [:config-def/path :config-def/root :config-def/value-type
                                                        :config-def/encrypted? :config-def/category
                                                        :config-def/service :config-def/sensitivity
                                                        :config-def/function]}]) .
           :in $ ?value-id
           :where
           [?e :config.value/id ?value-id]
           (not [?e :config.value/deleted-at])]
         db value-id)))

(defn list-node-values
  "List active values defined directly on a node."
  [db root tenant node-id]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! node-id :node-id)
  (ensure-valid-root! root)
  (let [node (or (get-config-node db node-id)
                 (throw (ex-info "Config node not found" {:node-id node-id})))
        _ (when (not= root (:config.node/root node))
            (throw (ex-info "Config node root mismatch"
                            {:requested-root root
                             :node-root (:config.node/root node)
                             :node-id node-id})))
        _ (when (not= tenant (:config.node/tenant node))
            (throw (ex-info "Config node tenant mismatch"
                            {:requested-tenant tenant
                             :node-tenant (:config.node/tenant node)
                             :node-id node-id})))
        node-eid (config-node-eid db node-id)]
    (->> (d/q '[:find [(pull ?e [* {:config.value/node [:config.node/id :config.node/root :config.node/tenant
                                                        :config.node/label :config.node/enabled?]}
                                   {:config.value/definition [:config-def/path :config-def/root :config-def/value-type
                                                              :config-def/encrypted? :config-def/category
                                                              :config-def/service :config-def/sensitivity
                                                              :config-def/function]}]) ...]
                :in $ ?node
                :where
                [?e :config.value/node ?node]
                (not [?e :config.value/deleted-at])]
              db node-eid)
         (sort-by #(get-in % [:config.value/definition :config-def/path]))
         vec)))

(defn set-node-value!
  "Create or update a node-scoped config value."
  [conn {:keys [root tenant node-id path value master-key]}]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! node-id :node-id)
  (ensure-present-string! path :path)
  (ensure-valid-root! root)
  (let [db @conn
        node (or (get-config-node db node-id)
                 (throw (ex-info "Config node not found" {:node-id node-id})))
        _ (ensure-node-enabled! node)
        _ (when (not= root (:config.node/root node))
            (throw (ex-info "Config node root mismatch"
                            {:requested-root root
                             :node-root (:config.node/root node)
                             :node-id node-id})))
        _ (when (not= tenant (:config.node/tenant node))
            (throw (ex-info "Config node tenant mismatch"
                            {:requested-tenant tenant
                             :node-tenant (:config.node/tenant node)
                             :node-id node-id})))
        definition (or (get-definition db path)
                       (throw (ex-info "Config definition not found" {:path path})))
        _ (when (not= root (:config-def/root definition))
            (throw (ex-info "Config definition root mismatch"
                            {:path path
                             :requested-root root
                             :definition-root (:config-def/root definition)})))
        encoded (encode-value value
                              (:config-def/value-type definition)
                              (:config-def/encrypted? definition)
                              master-key)
        now (System/currentTimeMillis)
        value-id (make-node-value-id root tenant node-id path)
        existing (config-node-value-entity db value-id)
        value-changed? (or (nil? existing)
                           (:config.value/deleted-at existing)
                           (not= encoded (:config.value/raw existing)))
        action (cond
                 (nil? existing) :created
                 value-changed? :updated
                 :else :unchanged)]
    (when value-changed?
      (let [tx-data (cond-> [(cond-> {:config.value/id value-id
                                      :config.value/root root
                                      :config.value/tenant tenant
                                      :config.value/node [:config.node/id node-id]
                                      :config.value/definition [:config-def/path path]
                                      :config.value/raw encoded
                                      :config.value/updated-at now}
                               (nil? existing) (assoc :config.value/created-at now))]
                      (:config.value/deleted-at existing)
                      (conj [:db/retract
                             [:config.value/id value-id]
                             :config.value/deleted-at
                             (:config.value/deleted-at existing)]))]
        (d/transact conn {:tx-data tx-data})))
    action))

(defn delete-node-value!
  "Soft-delete a node-scoped config value."
  [conn {:keys [root tenant node-id path]}]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! node-id :node-id)
  (ensure-present-string! path :path)
  (ensure-valid-root! root)
  (let [existing (get-node-value @conn root tenant node-id path)]
    (when existing
      (let [now (System/currentTimeMillis)]
        (d/transact conn {:tx-data [{:db/id [:config.value/id (make-node-value-id root tenant node-id path)]
                                     :config.value/deleted-at now
                                     :config.value/updated-at now}]}))
      true)))

(defn- ensure-legacy-dataset-pipeline-projection-definitions!
  [conn projection-values]
  (doseq [[path definition] legacy-dataset-pipeline-projection-definitions
          :when (contains? projection-values path)]
    (upsert-definition! conn (assoc definition :path path))))

(defn- pipeline-materialization-contexts
  [db pipeline-id]
  (->> (for [tenant (list-tenants db)
             node (list-config-nodes db tenant :dataset)
             :let [node-id (:config.node/id node)
                   parsed-node (parse-dataset-node-id node-id)]
             :when (and (:config.node/enabled? node)
                        (= :materialization (:kind parsed-node))
                        (= pipeline-id (:pipeline-id parsed-node)))]
         {:tenant tenant
          :node-id node-id})
       distinct
       vec))

(defn backfill-dataset-pipeline-config-projections!
  "Backfill legacy pipeline projection fields into dataset-tree config nodes."
  [conn pipeline-id {:keys [name source-type retract-legacy-attrs?]
                     :or {retract-legacy-attrs? false}}]
  (ensure-present-string! pipeline-id :pipeline-id)
  (let [projection-values (cond-> {}
                            (some? name) (assoc "pipeline.ui.name" name)
                            (some? source-type) (assoc "pipeline.source.type" source-type))
        db @conn
        contexts (pipeline-materialization-contexts db pipeline-id)
        legacy-record (get-dataset-pipeline db pipeline-id)]
    (ensure-legacy-dataset-pipeline-projection-definitions! conn projection-values)
    (when (and (seq projection-values)
               (empty? contexts))
      (log/warn "Legacy dataset pipeline projection could not be backfilled because no enabled materialization nodes were found"
                {:pipeline-id pipeline-id
                 :projection-keys (keys projection-values)}))
    (let [{:keys [values-backfilled values-conflicting-config]}
          (reduce
           (fn [acc {:keys [tenant node-id]}]
             (reduce-kv
              (fn [inner-acc path value]
                (let [existing (get-node-value @conn :dataset tenant node-id path)
                      definition (:config.value/definition existing)
                      decoded-existing (when existing
                                         (decode-value (:config.value/raw existing)
                                                       (:config-def/value-type definition)
                                                       (:config-def/encrypted? definition)
                                                       nil))]
                  (cond
                    (and (some? existing) (not= decoded-existing value))
                    (do
                      (log/warn "Dropping legacy dataset.pipeline projection because config value diverges"
                                {:pipeline-id pipeline-id
                                 :tenant tenant
                                 :node-id node-id
                                 :path path
                                 :legacy-value value
                                 :config-value decoded-existing})
                      (update inner-acc :values-conflicting-config inc))

                    (some? existing)
                    inner-acc

                    :else
                    (if (= :unchanged
                           (set-node-value! conn {:root :dataset
                                                  :tenant tenant
                                                  :node-id node-id
                                                  :path path
                                                  :value value
                                                  :master-key nil}))
                      inner-acc
                      (update inner-acc :values-backfilled inc)))))
              acc
              projection-values))
           {:values-backfilled 0
            :values-conflicting-config 0}
           contexts)
          retract-tx-data (when (and retract-legacy-attrs?
                                     (seq contexts)
                                     legacy-record)
                            (cond-> []
                              (some? (:dataset.pipeline/name legacy-record))
                              (conj [:db/retract
                                     [:dataset.pipeline/id pipeline-id]
                                     :dataset.pipeline/name
                                     (:dataset.pipeline/name legacy-record)])
                              (some? (:dataset.pipeline/source-type legacy-record))
                              (conj [:db/retract
                                     [:dataset.pipeline/id pipeline-id]
                                     :dataset.pipeline/source-type
                                     (:dataset.pipeline/source-type legacy-record)])))
          legacy-attrs-retracted (count retract-tx-data)]
      (when (seq retract-tx-data)
        (d/transact conn {:tx-data retract-tx-data}))
      {:pipeline-id pipeline-id
       :materialization-nodes (count contexts)
       :values-backfilled values-backfilled
       :values-conflicting-config values-conflicting-config
       :legacy-attrs-retracted legacy-attrs-retracted
       :missing-materialization-nodes? (empty? contexts)})))

(defn migrate-legacy-dataset-pipeline-projections!
  "Idempotently migrate legacy durable pipeline projection attrs into the config tree."
  [conn]
  (let [legacy-pipelines (->> (list-dataset-pipelines @conn)
                              (filter #(or (some? (:dataset.pipeline/name %))
                                           (some? (:dataset.pipeline/source-type %))))
                              vec)
        result (reduce
                (fn [acc pipeline-record]
                  (let [migration (backfill-dataset-pipeline-config-projections!
                                   conn
                                   (:dataset.pipeline/id pipeline-record)
                                   {:name (:dataset.pipeline/name pipeline-record)
                                    :source-type (:dataset.pipeline/source-type pipeline-record)
                                    :retract-legacy-attrs? true})]
                    (-> acc
                        (update :pipelines-with-legacy-projections inc)
                        (update :pipelines-migrated + (if (:missing-materialization-nodes? migration) 0 1))
                        (update :pipelines-without-materialization-nodes + (if (:missing-materialization-nodes? migration) 1 0))
                        (update :values-backfilled + (:values-backfilled migration))
                        (update :values-conflicting-config + (:values-conflicting-config migration))
                        (update :legacy-attrs-retracted + (:legacy-attrs-retracted migration)))))
                {:pipelines-with-legacy-projections 0
                 :pipelines-migrated 0
                 :pipelines-without-materialization-nodes 0
                 :values-backfilled 0
                 :values-conflicting-config 0
                 :legacy-attrs-retracted 0}
                legacy-pipelines)]
    (cond
      (pos? (:values-conflicting-config result))
      (log/warn "Migrated legacy dataset pipeline projections (config divergence dropped legacy values)" result)

      (pos? (:pipelines-with-legacy-projections result))
      (log/info "Migrated legacy dataset pipeline projections" result))
    result))

;; =============================================================================
;; Global (inherit) fallback
;; =============================================================================

(declare get-config-node-by-tenant-config-key
         fetch-node-values-for-chain)

(def ^:private global-tenant-config-key "default")

(defn- definition-ownership
  "Return :fork or :inherit for a path. Missing attribute defaults to :fork."
  [db path]
  (or (:config-def/ownership (get-definition db path))
      :fork))

(defn- global-root-node
  "Root node for the global (__global__) tenant at this root, or nil if absent."
  [db root]
  (get-config-node-by-tenant-config-key db core/global-tenant root global-tenant-config-key))

(defn- prefetch-global-chain
  "Prefetch the ancestor chain for the global tenant at this root, or nil."
  [db root]
  (when-let [root-node (global-root-node db root)]
    (prefetch-ancestor-chain db root-node)))

(defn- fetch-global-values-for-chain
  [db root chain]
  (when (seq chain)
    (fetch-node-values-for-chain db root core/global-tenant chain)))

(defn- walk-chain-for-path
  "Walk a node chain looking for a single path. `chain` is a vector of node entities
   from most-specific to root. `values-by-node` is the prefetched map of
   {node-id {path value-entity}}.

   When head-preselected? is true, the first node's enabled? flag is not checked
   (caller already validated). For all other nodes, a disabled node stops the walk.

   Returns {:value :traversal-path :winning-node :stop-reason [:stopped-at]}."
  [chain values-by-node path {:keys [head-preselected?] :or {head-preselected? false}}]
  (loop [idx 0
         traversal-path []]
    (if (>= idx (count chain))
      {:value nil
       :traversal-path traversal-path
       :winning-node nil
       :stop-reason :root-exhausted}
      (let [node (nth chain idx)
            nid (:config.node/id node)
            traversal-path' (conj traversal-path nid)
            enabled? (or (:config.node/enabled? node)
                         (and head-preselected? (zero? idx)))]
        (if-not enabled?
          {:value nil
           :traversal-path traversal-path'
           :winning-node nil
           :stop-reason :disabled-node
           :stopped-at nid}
          (if-let [v (get-in values-by-node [nid path])]
            {:value v
             :traversal-path traversal-path'
             :winning-node nid
             :stop-reason :matched}
            (recur (inc idx) traversal-path')))))))

(defn- combine-trace-with-global
  "Given a base (tenant-layer) trace and a resolved tenant-walk result, optionally
   fall through to the global chain when the tenant result missed and the path is
   inherit-owned. Returns {:value :trace} with possibly a :global-fallback segment."
  [base-trace tenant-walk inherit? global-chain global-values-by-node path]
  (cond
    ;; Tenant hit — return with tenant layer.
    (:value tenant-walk)
    {:value (:value tenant-walk)
     :trace (cond-> (assoc base-trace
                           :traversal-path (:traversal-path tenant-walk)
                           :winning-node (:winning-node tenant-walk)
                           :path path
                           :stop-reason (:stop-reason tenant-walk)
                           :winning-layer :tenant)
              (:stopped-at tenant-walk) (assoc :stopped-at (:stopped-at tenant-walk)))}

    ;; Tenant miss, fork-owned — no fallback.
    (not inherit?)
    {:value nil
     :trace (cond-> (assoc base-trace
                           :traversal-path (:traversal-path tenant-walk)
                           :winning-node nil
                           :path path
                           :stop-reason (:stop-reason tenant-walk)
                           :winning-layer nil)
              (:stopped-at tenant-walk) (assoc :stopped-at (:stopped-at tenant-walk)))}

    ;; Tenant miss, inherit-owned — walk global chain.
    :else
    (let [global-walk (if (seq global-chain)
                        (walk-chain-for-path global-chain global-values-by-node path {})
                        {:value nil :traversal-path [] :winning-node nil :stop-reason :no-global-tree})
          global-segment (select-keys global-walk [:traversal-path :winning-node :stop-reason :stopped-at])]
      (if (:value global-walk)
        {:value (:value global-walk)
         :trace (cond-> (assoc base-trace
                               :traversal-path (:traversal-path tenant-walk)
                               :winning-node (:winning-node global-walk)
                               :path path
                               :stop-reason :matched-global
                               :winning-layer :global
                               :global-fallback global-segment)
                  (:stopped-at tenant-walk) (assoc :stopped-at (:stopped-at tenant-walk)))}
        {:value nil
         :trace (cond-> (assoc base-trace
                               :traversal-path (:traversal-path tenant-walk)
                               :winning-node nil
                               :path path
                               :stop-reason (:stop-reason tenant-walk)
                               :winning-layer nil
                               :global-fallback global-segment)
                  (:stopped-at tenant-walk) (assoc :stopped-at (:stopped-at tenant-walk)))}))))

;; =============================================================================
;; Node-value resolution
;; =============================================================================

(defn resolve-node-value-with-trace
  "Resolve a node-scoped config value by walking the node and its parents.

   If the tenant chain misses and the definition's :config-def/ownership is
   :inherit, fall through to the global (__global__) tenant's chain. The trace
   records the layer that won (:tenant or :global) and a :global-fallback
   subtrace when the fallback fired.

   Returns:
   {:value value-entity-or-nil
    :trace {:selected-root :runtime
            :selected-tenant \"ka\"
            :selected-node \"runtime/frontpage\"
            :traversal-path [\"runtime/frontpage\" \"runtime/base\"]
            :winning-node \"runtime/base\"
            :winning-layer :tenant   ; or :global or nil
            :path \"skills.retrieval.top-k\"
            :stop-reason :matched    ; or :matched-global / :root-exhausted / :disabled-node
            :global-fallback {...}   ; present when fallback was considered}}"
  [db root tenant node-id path]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! node-id :node-id)
  (ensure-present-string! path :path)
  (ensure-valid-root! root)
  (let [selected-node (require-config-node! db root tenant node-id)
        _ (ensure-node-enabled! selected-node)
        requested-tenant-config-key (:config.node/tenant-config-key selected-node)
        tenant-chain (prefetch-ancestor-chain db selected-node)
        tenant-values (fetch-node-values-for-chain db root tenant tenant-chain)
        tenant-walk (walk-chain-for-path tenant-chain tenant-values path
                                         {:head-preselected? true})
        base-trace {:selected-root root
                    :selected-tenant tenant
                    :selected-node node-id
                    :requested-tenant-config-key requested-tenant-config-key
                    :tenant-root-tenant-config-key "default"}
        inherit? (= :inherit (definition-ownership db path))
        global-chain (when inherit? (prefetch-global-chain db root))
        global-values (when inherit? (fetch-global-values-for-chain db root global-chain))]
    (combine-trace-with-global base-trace tenant-walk inherit? global-chain global-values path)))

(defn resolve-node-value
  "Resolve a node-scoped config value by walking the node and its parents."
  [db root tenant node-id path]
  (:value (resolve-node-value-with-trace db root tenant node-id path)))

(defn resolve-global-value-with-trace
  "Resolve a single path directly from the __global__ tenant chain, bypassing any
   tenant tree. Returns nil value if the global tenant has no tree.

   Used by the accessor when a tenant has no tree at all but a path's ownership
   is :inherit."
  [db root path]
  (ensure-present-string! path :path)
  (ensure-valid-root! root)
  (let [chain (prefetch-global-chain db root)
        values-by-node (fetch-global-values-for-chain db root chain)
        walk (if (seq chain)
               (walk-chain-for-path chain values-by-node path {})
               {:value nil :traversal-path [] :winning-node nil :stop-reason :no-global-tree})]
    {:value (:value walk)
     :trace {:selected-root root
             :selected-tenant core/global-tenant
             :selected-node (some-> chain first :config.node/id)
             :requested-tenant-config-key global-tenant-config-key
             :tenant-root-tenant-config-key global-tenant-config-key
             :traversal-path (:traversal-path walk)
             :winning-node (:winning-node walk)
             :winning-layer (when (:value walk) :global)
             :path path
             :stop-reason (if (:value walk) :matched-global (:stop-reason walk))}}))

(defn- fetch-node-values-for-chain
  "Batch-fetch all active node-scoped values for an ancestor chain.
   Returns a map of {node-id {path value-entity}}."
  [db root tenant chain]
  (let [node-ids (mapv :config.node/id chain)]
    (when (seq node-ids)
      (let [values (d/q '[:find [(pull ?e [* {:config.value/node [:config.node/id]}
                                              {:config.value/definition
                                               [:config-def/path :config-def/root
                                                :config-def/value-type :config-def/encrypted?]}]) ...]
                           :in $ ?root ?tenant [?node-id ...]
                           :where
                           [?node :config.node/id ?node-id]
                           [?e :config.value/node ?node]
                           [?e :config.value/root ?root]
                           [?e :config.value/tenant ?tenant]
                           (not [?e :config.value/deleted-at])]
                         db root tenant node-ids)]
        (reduce (fn [acc v]
                  (let [nid (get-in v [:config.value/node :config.node/id])
                        path (get-in v [:config.value/definition :config-def/path])]
                    (assoc-in acc [nid path] v)))
                {}
                values)))))

(defn- ownership-by-path
  [db paths]
  (into {}
        (map (fn [path] [path (definition-ownership db path)]))
        paths))

(defn- resolve-node-values-with-prefetched-values
  "Resolve each path by walking the tenant chain. On miss, if the path's
   definition is :inherit-owned, fall through to the global chain.

   global-chain and global-values-by-node may be nil when no inherit-owned
   paths are present or no global tree exists."
  [root tenant selected-node-id selected-tenant-config-key chain values-by-node paths
   & [{:keys [ownership-map global-chain global-values-by-node]}]]
  (let [base-trace {:selected-root root
                    :selected-tenant tenant
                    :selected-node selected-node-id
                    :requested-tenant-config-key selected-tenant-config-key
                    :tenant-root-tenant-config-key "default"}
        resolve-path (fn [path]
                       (let [tenant-walk (walk-chain-for-path chain values-by-node path
                                                              {:head-preselected? true})
                             inherit? (= :inherit (get ownership-map path :fork))]
                         (combine-trace-with-global base-trace tenant-walk inherit?
                                                    global-chain global-values-by-node path)))]
    {:results (into {}
                    (map (fn [path] [path (resolve-path path)]))
                    paths)
     :chain chain}))

(defn- resolve-node-values-for-chain
  [db root tenant selected-node-id selected-tenant-config-key chain paths]
  (let [values-by-node (fetch-node-values-for-chain db root tenant chain)
        ownership-map (ownership-by-path db paths)
        any-inherit? (some #(= :inherit %) (vals ownership-map))
        global-chain (when any-inherit? (prefetch-global-chain db root))
        global-values (when any-inherit? (fetch-global-values-for-chain db root global-chain))]
    (resolve-node-values-with-prefetched-values
     root tenant selected-node-id selected-tenant-config-key chain values-by-node paths
     {:ownership-map ownership-map
      :global-chain global-chain
      :global-values-by-node global-values})))

(defn resolve-node-values-batch
  "Resolve multiple config paths against the same node in a single batch.

   Prefetches the ancestor chain once, then batch-fetches all values across
   all ancestors and resolves each path by walking the chain.

   Returns:
   {:results {\"path\" {:value value-entity-or-nil :trace trace-map}}
    :chain [node-entities...]}"
  [db root tenant node-id paths]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! node-id :node-id)
  (ensure-valid-root! root)
  (let [selected-node (require-config-node! db root tenant node-id)]
    (ensure-node-enabled! selected-node)
    (resolve-node-values-for-chain db root tenant node-id
                                   (:config.node/tenant-config-key selected-node)
                                   (prefetch-ancestor-chain db selected-node)
                                   paths)))

(defn preview-node-parent-change
  "Preview the effective-config impact of changing a node's parent without
   committing the mutation.

   Returns both the current and previewed resolution results for the supplied
   paths so callers can diff values and winning nodes."
  [db root tenant node-id parent-id paths]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! node-id :node-id)
  (ensure-valid-root! root)
  (let [selected-node (require-config-node! db root tenant node-id)
        current-parent-id (get-in selected-node [:config.node/parent :config.node/id])
        normalized-parent-id (when-not (str/blank? parent-id) parent-id)]
    (ensure-node-enabled! selected-node)
    (when-let [preview-parent-id normalized-parent-id]
      (let [parent (or (get-config-node db preview-parent-id)
                       (throw (ex-info "Config node not found"
                                       {:node-id preview-parent-id})))]
        (validate-config-node-parent! db selected-node parent)))
    (let [current-chain (prefetch-ancestor-chain db selected-node)
          preview-node (assoc selected-node :config.node/parent (when normalized-parent-id
                                                                 {:config.node/id normalized-parent-id}))
          preview-chain (prefetch-ancestor-chain db preview-node)]
      {:current-parent-id current-parent-id
       :preview-parent-id normalized-parent-id
       :before (resolve-node-values-for-chain db root tenant node-id
                                              (:config.node/tenant-config-key selected-node)
                                              current-chain paths)
       :after (resolve-node-values-for-chain db root tenant node-id
                                             (:config.node/tenant-config-key selected-node)
                                             preview-chain paths)})))

(defn preview-node-value-reset-change
  "Preview the effective-config impact of removing a direct node value without
   committing the mutation."
  [db root tenant node-id path]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! node-id :node-id)
  (ensure-present-string! path :path)
  (ensure-valid-root! root)
  (let [selected-node (require-config-node! db root tenant node-id)
        current-chain (prefetch-ancestor-chain db selected-node)
        values-by-node (fetch-node-values-for-chain db root tenant current-chain)
        after-values-by-node (update values-by-node node-id dissoc path)
        ownership-map (ownership-by-path db [path])
        inherit? (= :inherit (get ownership-map path))
        global-chain (when inherit? (prefetch-global-chain db root))
        global-values (when inherit? (fetch-global-values-for-chain db root global-chain))
        fallback-opts {:ownership-map ownership-map
                       :global-chain global-chain
                       :global-values-by-node global-values}]
    (ensure-node-enabled! selected-node)
    {:path path
     :before (resolve-node-values-with-prefetched-values root tenant node-id
                                                         (:config.node/tenant-config-key selected-node)
                                                         current-chain values-by-node [path]
                                                         fallback-opts)
     :after (resolve-node-values-with-prefetched-values root tenant node-id
                                                        (:config.node/tenant-config-key selected-node)
                                                        current-chain after-values-by-node [path]
                                                        fallback-opts)}))

;; =============================================================================
;; Pipeline Operations (Dataset V2)
;; =============================================================================

;; Pipeline property mapping: keyword -> database path
(def pipeline-property-to-path
  "Maps pipeline property keywords to their database paths.
   Organized by functional area:
   - pipeline.ui.* - Display/identity properties
   - pipeline.source.* - Data source configuration
   - pipeline.documents.* - Shared document filtering
   - pipeline.chunks.* - Chunking strategy
   - pipeline.search-phrases.* - Search phrase generation
   - pipeline.storage.* - Collection names and storage
   - pipeline.operations.* - Execution parameters"
  {;; UI/Identity
   :name                      "pipeline.ui.name"
   :image                     "pipeline.ui.image"
   :description               "pipeline.ui.description"

   ;; Data Source
   :source-type               "pipeline.source.type"
   :website-sitemap-url       "pipeline.source.website.sitemap-url"
   :website-base-url          "pipeline.source.website.base-url"
   :folder-path               "pipeline.source.folder.path"
   :kudos-use-preprod         "pipeline.source.kudos.use-preprod"
   :kudos-starting-page       "pipeline.source.kudos.starting-page"
   :episerver-xml-path        "pipeline.source.episerver.xml-path"
   :episerver-language        "pipeline.source.episerver.language"
   :episerver-include-page-types "pipeline.source.episerver.include-page-types"
   :kudos-document-types      "pipeline.source.kudos.document-types"
   :kudos-transducer          "pipeline.source.kudos.transducer"

   ;; Shared Document Filtering
   :document-limit            "pipeline.documents.limit"
   :document-offset           "pipeline.documents.offset"

   ;; Chunking
   :chunk-strategy            "pipeline.chunks.strategy"
   :chunk-minimum-length      "pipeline.chunks.minimum-length"
   :chunk-maximum-length      "pipeline.chunks.maximum-length"
   :chunk-split-max-length    "pipeline.chunks.split-max-length"

   ;; Search Phrases
   :search-phrases-model      "pipeline.search-phrases.model"
   :search-phrases-fallback   "pipeline.search-phrases.fallback-model"
   :search-phrases-prompt     "pipeline.search-phrases.prompt"

   ;; Storage/Collections
   :collection-prefix         "pipeline.storage.collection-prefix"
   :docs-collection           "pipeline.storage.docs-collection"
   :chunks-collection         "pipeline.storage.chunks-collection"
   :phrases-collection        "pipeline.storage.phrases-collection"

   ;; Operations
   :parallelism-documents     "pipeline.operations.parallelism-documents"
   :parallelism-store         "pipeline.operations.parallelism-store"
   :max-document-failures     "pipeline.operations.max-document-failures"})

;; Reverse mapping: database path -> keyword
(def path-to-pipeline-property
  "Reverse mapping from database paths to pipeline property keywords."
  (into {} (map (fn [[k v]] [v k]) pipeline-property-to-path)))

(def pipeline-property-paths
  "Config paths for pipeline properties.
   These paths are used with pipeline scope (pipeline ID in scope field, not path).
   Enables full 8-level inheritance for pipeline properties."
  (set (vals pipeline-property-to-path)))

(def dataset-runtime-property-to-path
  "Dataset-level runtime bindings resolved from the canonical dataset node."
  (select-keys pipeline-property-to-path
               [:collection-prefix
                :docs-collection
                :chunks-collection
                :phrases-collection]))

(def path-to-dataset-runtime-property
  "Reverse mapping from dataset runtime config paths to keywords."
  (into {} (map (fn [[k v]] [v k]) dataset-runtime-property-to-path)))

(def dataset-runtime-property-paths
  "Config paths that runtime callers may resolve from a canonical dataset ref."
  (set (vals dataset-runtime-property-to-path)))

(def dataset-runtime-properties
  "Dataset-level runtime properties exposed by canonical dataset refs."
  (set (keys dataset-runtime-property-to-path)))

(def pipeline-properties
  "Standard properties that pipelines can have (as keywords)."
  (set (keys pipeline-property-to-path)))

(defn pipeline-property-path
  "Get the config path for a pipeline property.
   Example: (pipeline-property-path :name) => \"pipeline.ui.name\"
            (pipeline-property-path :source-type) => \"pipeline.source.type\""
  [property]
  (get pipeline-property-to-path property))

;; =============================================================================
;; Skill Configuration (Scope-Based Design)
;; =============================================================================

(def skill-property-to-path
  "Canonical runtime config paths aligned to builtin skills.
   These values are typically resolved with pipeline scope because dataset-specific
   tuning still matters, but the parameter names live under skills.* so the config
   model reflects runtime skills rather than ingestion pipelines."
  {:query-planner-enabled                    "skills.query-planner.enabled"
   :query-planner-prompt                     "skills.query-planner.prompt"
   :query-planner-max-phrases                "skills.query-planner.max-phrases"
   :query-planner-expansion-mode             "skills.query-planner.expansion-mode"

   :retrieval-phrase-gen-prompt              "skills.retrieval.phrase-gen-prompt"
   :retrieval-top-k                          "skills.retrieval.top-k"
   :retrieval-max-per-document               "skills.retrieval.max-per-document"
   :retrieval-query-aware-boost              "skills.retrieval.query-aware-boost"
   :retrieval-strategy-weights               "skills.retrieval.strategy-weights"
   :retrieval-strategy-contribution-caps     "skills.retrieval.strategy-contribution-caps"
   :retrieval-title-fields                   "skills.retrieval.title-fields"
   :retrieval-doc-title-chunk-fanout         "skills.retrieval.doc-title-chunk-fanout"
   :retrieval-auto-filter-rules              "skills.retrieval.auto-filter-rules"
   :retrieval-enrichment-types               "skills.retrieval.enrichment-types"
   :retrieval-merge-mode                     "skills.retrieval.merge-mode"
   :retrieval-rrf-k                          "skills.retrieval.rrf-k"
   ;; Slice 23: user-intent first-pass union toggles.
   :retrieval-user-intent-union-enabled      "skills.retrieval.user-intent-union.enabled"
   :retrieval-user-intent-union-mode         "skills.retrieval.user-intent-union.mode"
   :retrieval-user-intent-union-cap          "skills.retrieval.user-intent-union.cap"
   :retrieval-user-intent-union-rrf-k        "skills.retrieval.user-intent-union.rrf-k"

   :rerank-enabled                           "skills.rerank.enabled"
   :rerank-top-k                             "skills.rerank.top-k"
   :rerank-context-min-chunks                "skills.rerank.context.min-chunks"
   :rerank-context-relative-score-threshold  "skills.rerank.context.relative-score-threshold"

   ;; Mode-namespaced rerank knobs (Option A: explicit mode dimension).
   ;; build-rag-skill-params reads :rerank-rag-* (smaller chunks for LLM
   ;; synthesis); build-retrieval-skill-params reads :rerank-retrieval-*
   ;; (larger chunks for raw return). See plans/completed/cozy-herding-spring.md.
   :rerank-rag-max-chunk-length              "skills.rerank.rag.max-chunk-length"
   :rerank-rag-max-total-length              "skills.rerank.rag.max-total-length"
   :rerank-rag-max-context-length            "skills.rerank.rag.max-context-length"
   :rerank-rag-context-top-k                 "skills.rerank.rag.context.top-k"
   :rerank-rag-context-max-chunk-length      "skills.rerank.rag.context.max-chunk-length"

   :rerank-retrieval-max-chunk-length        "skills.rerank.retrieval.max-chunk-length"
   :rerank-retrieval-max-total-length        "skills.rerank.retrieval.max-total-length"
   :rerank-retrieval-max-context-length      "skills.rerank.retrieval.max-context-length"
   :rerank-retrieval-context-top-k           "skills.rerank.retrieval.context.top-k"
   :rerank-retrieval-context-max-chunk-length "skills.rerank.retrieval.context.max-chunk-length"

   :synthesis-model                          "skills.synthesis.model"
   :synthesis-temperature                    "skills.synthesis.temperature"
   :synthesis-max-tokens                     "skills.synthesis.max-tokens"
   :synthesis-system-prompt                  "skills.synthesis.system-prompt"
   :synthesis-generation-prompt              "skills.synthesis.generation-prompt"
   :synthesis-max-docs                       "skills.synthesis.max-docs"})

;; Reverse mapping: database path -> keyword
(def path-to-skill-property
  "Reverse mapping from database paths to skill property keywords."
  (into {} (map (fn [[k v]] [v k]) skill-property-to-path)))

(def skill-property-paths
  "Config paths for skill properties.
   These paths are used with pipeline scope (skill ID in scope field, not path).
   Enables full 8-level inheritance for skill properties."
  (set (vals skill-property-to-path)))

(def skill-properties
  "Standard properties that skills can have (as keywords)."
  (set (keys skill-property-to-path)))

(defn skill-property-path
  "Get the config path for a skill property.
   Example: (skill-property-path :query-planner-prompt) => \"skills.query-planner.prompt\"
            (skill-property-path :retrieval-top-k) => \"skills.retrieval.top-k\""
  [property]
  (get skill-property-to-path property))

(defn list-tenants
  "Get all registered tenants from the config database.
   Returns a sorted vector of tenant ID strings."
  [db]
  (let [tenants (d/q '[:find [?id ...]
                       :where
                       [?e :tenant/id ?id]]
                     db)]
    (-> tenants sort vec)))

(defn get-tenant
  "Get a tenant pipeline by ID.
   Returns map with :tenant/id, :tenant/name, :tenant/created-at, :tenant/created-by
   or nil if not found."
  [db tenant-id]
  (d/q '[:find (pull ?e [:tenant/id :tenant/name :tenant/created-at :tenant/created-by]) .
         :in $ ?id
         :where [?e :tenant/id ?id]]
       db tenant-id))

(defn get-tenant-name
  "Get the display name for a tenant. Returns the name or the ID if no name is set."
  [db tenant-id]
  (or (d/q '[:find ?name .
             :in $ ?id
             :where
             [?e :tenant/id ?id]
             [?e :tenant/name ?name]]
           db tenant-id)
      tenant-id))

(defn get-all-tenant-names
  "Get a map of tenant-id -> display name for all tenants."
  [db]
  (into {}
        (d/q '[:find ?id ?name
               :where
               [?e :tenant/id ?id]
               [?e :tenant/name ?name]]
             db)))

(defn register-tenant!
  "Register a new tenant in the config database.
   Creates a tenant pipeline with the given ID and optional name.
   This is idempotent - calling it multiple times for the same tenant is safe.

   Args:
     conn - Datahike connection
     tenant-id - Unique tenant identifier (stable, used in config values)
     opts - Optional map with:
       :name - Display name (defaults to tenant-id)
       :created-by - User ID who created the tenant"
  ([conn tenant-id]
   (register-tenant! conn tenant-id {}))
  ([conn tenant-id {:keys [name created-by] :or {created-by "system"}}]
   (let [db @conn]
     ;; Only create if not already registered
     (when-not (get-tenant db tenant-id)
       (d/transact conn [{:tenant/id tenant-id
                          :tenant/name (or name tenant-id)
                          :tenant/created-at (java.util.Date.)
                          :tenant/created-by created-by}])))))

(defn rename-tenant!
  "Rename a tenant (change its display name).
   The tenant ID remains stable - only the display name changes.
   This does not affect any config values referencing the tenant.

   Args:
     conn - Datahike connection
     tenant-id - The tenant's stable ID
     new-name - The new display name

   Returns true if renamed, false if tenant not found."
  [conn tenant-id new-name]
  (let [db @conn
        tenant-eid (d/q '[:find ?e .
                          :in $ ?id
                          :where [?e :tenant/id ?id]]
                        db tenant-id)]
    (if tenant-eid
      (do
        (d/transact conn [{:db/id tenant-eid
                           :tenant/name new-name}])
        true)
      false)))

(declare get-dataset
         resolve-dataset-materialization-node!
         parse-dataset-node-id
         resolve-dataset-runtime-node!)

(defn list-datasets
  "List enabled datasets through the Dataset V2 model.

   With no tenant, returns all enabled dataset pipelines. With a tenant, returns
   only pipelines reachable from that tenant's canonical dataset/materialization
   nodes, optionally filtered by tenant-config-key."
  ([db tenant]
   (list-datasets db tenant nil))
  ([db tenant tenant-config-key]
   (let [enabled-pipelines (->> (list-dataset-pipelines db)
                                (filter :dataset.pipeline/enabled?)
                                vec)
         enabled-datasets (set (map :dataset.pipeline/id enabled-pipelines))]
     (if-not tenant
       (-> enabled-datasets sort vec)
       (let [materialization-keys (->> (list-config-nodes db tenant :dataset)
                                       (filter :config.node/enabled?)
                                       (keep (fn [node]
                                               (when-let [{:keys [kind dataset-id tenant-config-key pipeline-id]}
                                                          (parse-dataset-node-id (:config.node/id node))]
                                                 (when (= :materialization kind)
                                                   [dataset-id tenant-config-key pipeline-id]))))
                                       set)
             any-materialization-keys (->> materialization-keys
                                           (map (fn [[dataset-id _ pipeline-id]]
                                                  [dataset-id pipeline-id]))
                                           set)]
         (->> enabled-pipelines
              (keep (fn [dataset-pipeline]
                      (let [pipeline-id (:dataset.pipeline/id dataset-pipeline)
                            dataset-id (get-in dataset-pipeline [:dataset.pipeline/dataset :dataset/id])]
                        (when (or (and tenant-config-key
                                       (contains? materialization-keys
                                                  [dataset-id
                                                   (or tenant-config-key "_")
                                                   pipeline-id]))
                                  (and (nil? tenant-config-key)
                                       (contains? any-materialization-keys
                                                  [dataset-id pipeline-id])))
                          pipeline-id))))
              (filter enabled-datasets)
              distinct
              sort
              vec))))))

(defn list-all-pipelines
  "Get all enabled pipeline IDs across all tenants."
  [db]
  (list-datasets db nil nil))

(defn get-datasets-by-tenant
  "Get all pipelines grouped by tenant for the pipeline selector."
  [db tenants]
  (reduce
   (fn [acc tenant]
     (assoc acc tenant (list-datasets db tenant nil)))
   {}
   tenants))

(defn get-dataset-names
  "Get a map of pipeline-id -> display name for pipelines through Dataset V2."
  ([db tenant pipeline-ids]
   (get-dataset-names db tenant "default" pipeline-ids))
  ([db tenant tenant-config-key pipeline-ids]
   (reduce
    (fn [acc pipeline-id]
      (if-let [resolved-name (when-let [pipeline-record (get-dataset-pipeline db pipeline-id)]
                               (:dataset.pipeline/effective-name
                                (effective-dataset-pipeline-record
                                 db
                                 pipeline-record
                                 (cond-> {:tenant-config-key tenant-config-key}
                                   tenant (assoc :tenant tenant)))))]
        (assoc acc pipeline-id resolved-name)
        acc))
    {}
    pipeline-ids)))

(defn dataset-base-node-id
  "Build the canonical dataset base-node ID for a tenant-local dataset tree."
  [tenant dataset-id]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! dataset-id :dataset-id)
  (str "dataset/" tenant "/" dataset-id "/default"))

(defn default-dataset-tenant-config-key
  "Build the canonical explicit node tenant-config-key used for pipeline materialization traffic."
  [tenant-config-key pipeline-id]
  (ensure-present-string! pipeline-id :pipeline-id)
  (str (or tenant-config-key "_") "-" pipeline-id))

(defn dataset-materialization-node-id
  "Build the canonical dataset materialization node ID."
  [tenant tenant-config-key dataset-id pipeline-id]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! dataset-id :dataset-id)
  (ensure-present-string! pipeline-id :pipeline-id)
  (str "dataset/" tenant "/" dataset-id "/" (or tenant-config-key "_") "/" pipeline-id "/materialization"))

(defn- legacy-dataset-materialization-node-id
  "Build the legacy dataset materialization node ID used before canonical tenant-config-key encoding."
  [tenant dataset-id pipeline-id]
  (ensure-present-string! tenant :tenant)
  (ensure-present-string! dataset-id :dataset-id)
  (ensure-present-string! pipeline-id :pipeline-id)
  (str "dataset/" tenant "/" dataset-id "/" pipeline-id "/materialization"))

(defn- candidate-dataset-materialization-node-ids
  [tenant tenant-config-key dataset-id pipeline-id]
  (distinct
   [(dataset-materialization-node-id tenant tenant-config-key dataset-id pipeline-id)
    (legacy-dataset-materialization-node-id tenant dataset-id pipeline-id)]))

(defn- existing-dataset-materialization-node-id
  [db tenant tenant-config-key dataset-id pipeline-id]
  (some (fn [node-id]
          (when (get-config-node db node-id)
            node-id))
        (candidate-dataset-materialization-node-ids tenant tenant-config-key dataset-id pipeline-id)))

(defn parse-dataset-node-id
  "Parse canonical dataset node IDs into their structural parts."
  [node-id]
  (when (string? node-id)
    (or (when-let [[_ tenant dataset-id tenant-config-key pipeline-id]
                   (re-matches #"dataset/([^/]+)/([^/]+)/([^/]+)/([^/]+)/materialization" node-id)]
          {:kind :materialization
           :tenant tenant
           :dataset-id dataset-id
           :tenant-config-key tenant-config-key
           :pipeline-id pipeline-id})
        (when-let [[_ tenant dataset-id pipeline-id]
                   (re-matches #"dataset/([^/]+)/([^/]+)/([^/]+)/materialization" node-id)]
          {:kind :materialization
           :tenant tenant
           :dataset-id dataset-id
           :tenant-config-key nil
           :pipeline-id pipeline-id
           :legacy? true})
        (when-let [[_ tenant dataset-id]
                   (re-matches #"dataset/([^/]+)/([^/]+)/shared-materialization" node-id)]
          {:kind :shared-materialization
           :tenant tenant
           :dataset-id dataset-id})
        (when-let [[_ tenant dataset-id]
                   (re-matches #"dataset/([^/]+)/([^/]+)/default" node-id)]
          {:kind :base
           :tenant tenant
           :dataset-id dataset-id}))))

(defn dataset-node-dataset-id
  "Extract the canonical dataset ID from a dataset-root node ID."
  [node-id]
  (:dataset-id (parse-dataset-node-id node-id)))

(defn resolve-dataset-ref-materializations
  "Resolve a canonical runtime dataset ref to its dataset node and bound materialization pipelines.

   Runtime callers should identify datasets as {:tenant ... :dataset-config-key ...}.
   Materialization pipelines remain an internal concern resolved from the selected
   Dataset-root node plus canonical materialization node IDs."
  [db {:keys [tenant dataset-config-key tenant-config-key pipeline pipeline-id] :as _dataset-ref}]
  (when (and (not (str/blank? tenant))
             (not (str/blank? (or dataset-config-key tenant-config-key))))
    (let [{:keys [selected-node matched-node dataset-id dataset-config-key]}
          (resolve-dataset-runtime-node! db {:tenant tenant
                                             :dataset-config-key (or dataset-config-key tenant-config-key)})
          matched-node-id (:config.node/id matched-node)
          parsed-matched-node (parse-dataset-node-id matched-node-id)
          explicit-pipeline-id (or pipeline pipeline-id (:pipeline-id parsed-matched-node))
          explicit-pipeline-record (when explicit-pipeline-id
                                     (get-dataset-pipeline db explicit-pipeline-id))
          available-pipeline-ids (->> (list-config-nodes db tenant :dataset)
                                      (filter :config.node/enabled?)
                                      (keep (fn [node]
                                              (when-let [{:keys [kind pipeline-id]
                                                          parsed-dataset-id :dataset-id}
                                                         (parse-dataset-node-id (:config.node/id node))]
                                                (when (and (= :materialization kind)
                                                           (= parsed-dataset-id dataset-id))
                                                  pipeline-id))))
                                      set)
          candidate-pipeline-records (cond
                                       explicit-pipeline-record
                                       [explicit-pipeline-record]

                                       dataset-id
                                       (->> (list-dataset-pipelines db dataset-id)
                                            (filter :dataset.pipeline/enabled?)
                                            vec)

                                       :else
                                       [])
          pipeline-records (->> candidate-pipeline-records
                                (filter (fn [dataset-pipeline]
                                          (let [pipeline-record-dataset-id
                                                (get-in dataset-pipeline [:dataset.pipeline/dataset :dataset/id])
                                                pipeline-record-id (:dataset.pipeline/id dataset-pipeline)]
                                            (and (= dataset-id pipeline-record-dataset-id)
                                                 (contains? available-pipeline-ids pipeline-record-id)))))
                                vec)]
      (when (and explicit-pipeline-id (empty? pipeline-records))
        (throw (ex-info "Dataset ref does not resolve to a materialization pipeline"
                        {:tenant tenant
                         :dataset-config-key dataset-config-key
                         :dataset-id dataset-id
                         :pipeline-id explicit-pipeline-id
                         :node-id matched-node-id})))
      {:selected-node selected-node
       :matched-node matched-node
       :dataset-id dataset-id
       :dataset-config-key dataset-config-key
       :pipeline-records pipeline-records})))

(defn- resolve-dataset-runtime-dataset-id
  [db tenant requested-dataset-config-key]
  (when-not (str/blank? requested-dataset-config-key)
    (or (some-> (get-config-node-by-tenant-config-key db tenant :dataset requested-dataset-config-key)
                :config.node/id
                parse-dataset-node-id
                :dataset-id)
        (when (get-config-node db (dataset-base-node-id tenant requested-dataset-config-key))
          requested-dataset-config-key))))

(defn resolve-dataset-runtime-node!
  "Resolve a canonical dataset runtime ref to the dataset base node.

   Accepts canonical dataset refs shaped as {:tenant ... :dataset-config-key ...}.
   Legacy tenant-local dataset node keys still resolve, but callers receive the
   canonical dataset-config-key (the stable dataset ID) back."
  [db {:keys [tenant node-id dataset-config-key tenant-config-key dataset-id] :as _params}]
  (let [requested-dataset-config-key (or dataset-config-key tenant-config-key)
        matched-node (cond
                       node-id
                       (resolve-dataset-node! db {:tenant tenant
                                                  :node-id node-id})

                       (not (str/blank? requested-dataset-config-key))
                       (get-config-node-by-tenant-config-key db tenant :dataset requested-dataset-config-key)

                       :else
                       nil)
        resolved-dataset-id (or dataset-id
                                (some-> matched-node
                                        :config.node/id
                                        parse-dataset-node-id
                                        :dataset-id)
                                (resolve-dataset-runtime-dataset-id db tenant requested-dataset-config-key))]
    (when-not resolved-dataset-id
      ;; #434: classify WHICH client mistake this is, at the only point that can
      ;; tell. Both are client-side and they have different next steps:
      ;;   no datasets at all -> create one
      ;;   a ref that matches none -> check the name you sent
      ;; Collapsing them into one 4xx would repeat the category error one level
      ;; down — a correct status with a message that cannot say which mistake
      ;; was made.
      ;;
      ;; Cheap because `get-datasets-by-tenant` is in this namespace and defined
      ;; above: no new plumbing, one query.
      ;;
      ;; ⚠️ This CANNOT mean "the dataset has no indexed content". Resolution is
      ;; purely config-based and never consults Typesense, so a configured
      ;; dataset with an empty corpus resolves fine and never reaches here. That
      ;; is a different state at a different site — see the issue referenced in
      ;; the PR that added this.
      (let [any-datasets? (boolean (seq (get-datasets-by-tenant db tenant)))]
        (throw (ex-info (if any-datasets?
                          "Dataset ref does not match any configured dataset"
                          "No datasets are configured for this tenant")
                        {:tenant tenant
                         :dataset-config-key requested-dataset-config-key
                         :dataset-id dataset-id
                         :node-id node-id
                         ;; Read by the API error boundary to choose a status and
                         ;; a next step. Absent on every other ex-info, so the
                         ;; boundary's default stays 500 for genuine faults.
                         :digdir/client-error (if any-datasets?
                                                :dataset-ref-unknown
                                                :no-datasets-configured)}))))
    (let [selected-node-id (dataset-base-node-id tenant resolved-dataset-id)
          selected-node (or (get-config-node db selected-node-id)
                            (throw (ex-info "dataset runtime node not found"
                                            {:tenant tenant
                                             :dataset-id resolved-dataset-id
                                             :node-id selected-node-id})))]
      (ensure-node-root-and-tenant! selected-node :dataset tenant)
      (ensure-node-enabled! selected-node)
      {:selected-node selected-node
       :matched-node (or matched-node selected-node)
       :dataset-id resolved-dataset-id
       :dataset-config-key resolved-dataset-id})))

(defn- resolve-dataset-materialization-node!
  [db {:keys [tenant dataset-config-key tenant-config-key dataset-id pipeline-id]}]
  (let [base-dataset-config-key (or dataset-config-key tenant-config-key "default")
        materialization-node-id (when pipeline-id
                                  (existing-dataset-materialization-node-id db
                                                                            tenant
                                                                            base-dataset-config-key
                                                                            dataset-id
                                                                            pipeline-id))
        _ (when (and pipeline-id (nil? materialization-node-id))
            (throw (ex-info "dataset config node not found"
                            {:tenant tenant
                             :tenant-config-key base-dataset-config-key
                             :dataset-id dataset-id
                             :pipeline-id pipeline-id})))
        explicit-node-id (or materialization-node-id
                             (when dataset-id
                               (dataset-base-node-id tenant dataset-id)))]
    (resolve-dataset-node! db {:tenant tenant
                               :node-id explicit-node-id
                               :tenant-config-key (when-not explicit-node-id
                                                    base-dataset-config-key)
                               :dataset-id dataset-id
                               :pipeline-id pipeline-id})))

(defn get-dataset
  "Get dataset configuration by ID through the Dataset V2 node model.

   Uses batch resolution to fetch all dataset property values in a single
   ancestor chain walk rather than per-property queries."
  [db tenant tenant-config-key pipeline-id master-key]
  (when-let [dataset-pipeline (get-dataset-pipeline db pipeline-id)]
    (when (:dataset.pipeline/enabled? dataset-pipeline)
      (let [dataset-id (get-in dataset-pipeline [:dataset.pipeline/dataset :dataset/id])
            selected-node (resolve-dataset-materialization-node! db
                                                                 {:tenant tenant
                                                                  :tenant-config-key tenant-config-key
                                                                  :dataset-id dataset-id
                                                                  :pipeline-id pipeline-id})
        ;; Batch-fetch all definitions and validate roots
            all-defs-by-path (into {} (map (juxt :config-def/path identity))
                                     (get-all-definitions db))
        ;; Validate that every existing pipeline property definition is dataset-rooted
            _ (doseq [prop-path pipeline-property-paths]
                (when-let [definition (get all-defs-by-path prop-path)]
                  (when (not= :dataset (:config-def/root definition))
                    (throw (ex-info "Dataset V2 accessor requires a dataset-rooted definition"
                                    {:path prop-path
                                     :pipeline-id pipeline-id
                                     :definition-root (:config-def/root definition)})))))
        ;; Filter to only pipeline property paths that have dataset-rooted definitions
            valid-paths (filterv (fn [p]
                                   (when-let [d (get all-defs-by-path p)]
                                     (= :dataset (:config-def/root d))))
                                 pipeline-property-paths)
        ;; Batch-resolve all paths in a single ancestor walk
            {:keys [results]} (resolve-node-values-batch db :dataset tenant
                                                         (:config.node/id selected-node)
                                                         valid-paths)
            resolved-config
            (reduce
             (fn [acc prop-path]
               (let [{:keys [value]} (get results prop-path)]
                 (if value
                   (let [definition (get all-defs-by-path prop-path)
                         prop-name (get path-to-pipeline-property prop-path)
                         decoded (decode-value (:config.value/raw value)
                                               (:config-def/value-type definition)
                                               (:config-def/encrypted? definition)
                                               master-key)]
                     (assoc acc prop-name decoded))
                   acc)))
             {:id pipeline-id
              :dataset-id dataset-id
              :dataset-node-id (:config.node/id selected-node)}
             valid-paths)
            projection-drift (pipeline-projection-drift dataset-pipeline resolved-config)]
        (when projection-drift
          (log/warn "Dataset pipeline projection drift detected"
                    {:pipeline-id pipeline-id
                     :tenant tenant
                     :tenant-config-key tenant-config-key
                     :drift projection-drift}))
        (cond-> resolved-config
          (contains? resolved-config :name)
          (assoc :name (:name resolved-config))

          (contains? resolved-config :source-type)
          (assoc :source-type (:source-type resolved-config))

          projection-drift (assoc :projection-drift projection-drift))))))

(defn get-dataset-by-ref
  "Resolve dataset configuration from a runtime dataset ref.

   Returns canonical dataset fields at top level using a pure dataset-level
   runtime ref. Materialization pipelines are not exposed."
  [db {:keys [tenant _dataset-config-key] :as dataset-ref} master-key]
  (when-let [{:keys [selected-node dataset-id dataset-config-key]}
             (resolve-dataset-runtime-node! db dataset-ref)]
    (let [dataset-record (get-dataset-record db dataset-id)
          all-defs-by-path (into {} (map (juxt :config-def/path identity))
                                 (get-all-definitions db))
          valid-paths (filterv (fn [path-str]
                                 (when-let [definition (get all-defs-by-path path-str)]
                                   (= :dataset (:config-def/root definition))))
                               dataset-runtime-property-paths)
          {:keys [results]} (resolve-node-values-batch db :dataset tenant
                                                       (:config.node/id selected-node)
                                                       valid-paths)]
      (reduce
       (fn [acc path-str]
         (let [{:keys [value]} (get results path-str)]
           (if value
             (let [definition (get all-defs-by-path path-str)
                   prop-name (get path-to-dataset-runtime-property path-str)
                   decoded (decode-value (:config.value/raw value)
                                         (:config-def/value-type definition)
                                         (:config-def/encrypted? definition)
                                         master-key)]
               (assoc acc prop-name decoded))
             acc)))
       (cond-> {:id dataset-id
                :tenant tenant
                :dataset-config-key dataset-config-key
                :dataset-id dataset-id
                :dataset-node-id (:config.node/id selected-node)}
         (:dataset/name dataset-record) (assoc :name (:dataset/name dataset-record))
         (contains? dataset-record :dataset/description) (assoc :description (:dataset/description dataset-record))
         (contains? dataset-record :dataset/enabled?) (assoc :enabled? (:dataset/enabled? dataset-record)))
       valid-paths))))


;; =============================================================================
;; Initialization
;; =============================================================================

(defn init-config-db!
  "Initialize the config database with schema and default permissions.

   Args:
     conn - Datahike connection
     seed-permissions? - Whether to seed default permissions (default true)
     seed-agents? - Whether to seed builtin agents (default true)
     sync-admins? - Whether to sync admin permissions from ADMIN_USER_EMAILS (default true)"
  [conn & {:keys [seed-permissions? seed-agents? sync-admins?]
           :or {seed-permissions? true seed-agents? true sync-admins? true}}]
  (ensure-schema! conn)
  (migrate-legacy-dataset-pipeline-projections! conn)
  (when seed-permissions?
    (let [now (System/currentTimeMillis)]
      (doseq [perm schema/default-permissions]
        (try
          (d/transact conn {:tx-data [(assoc perm :permission/created-at now)]})
          (catch Exception e
            ;; Ignore "already exists" errors for idempotency
            (when-not (re-find #"unique constraint" (str (.getMessage e)))
              (throw e)))))))
  (when seed-agents?
    (require 'digdir.agents.db)
    (when-let [seed-agents-fn (resolve 'digdir.agents.db/seed-builtin-agents!)]
      (seed-agents-fn conn)))
  ;; Per-demo agents were retired in Phase 0; :builtin/docs-agent owns
  ;; the docs/* skill graphs via seed-builtin-agents! above. Demo
  ;; namespaces still register their skills and skill graphs via
  ;; their own register! fns, called from skills-init/initialize!.
  ;; Sync admin permissions from ADMIN_USER_EMAILS env var
  (when sync-admins?
    (try
      (require 'digdir.config.permissions)
      (when-let [sync-fn (resolve 'digdir.config.permissions/sync-admin-permissions!)]
        (sync-fn conn))
      (catch Exception e
        (println "Note: Could not sync admin permissions:" (.getMessage e))))))

(comment
  ;; Legacy tuple-scoped config has been retired from the live database model.
  ;; The remaining helpers in this namespace are V2 tree access and historical
  ;; export parsing utilities such as `make-config-id` / `parse-config-id`.)
)
