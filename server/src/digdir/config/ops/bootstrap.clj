(ns digdir.config.ops.bootstrap
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.structure :as structure]))

(def bootstrap-root-defaults
  ;; The "prod" platform leaf is the GENERIC-API default and is exercised by
  ;; test-bootstrap-config-tree-supports-platform-root-through-generic-api,
  ;; which asserts the node id and reads a base value up through the leaf.
  ;;
  ;; No PRODUCTION platform bootstrap uses it: setup/workflow.clj,
  ;; setup/config.clj, e2e/seed.clj and (since #275) ops/topology.clj all pass
  ;; :single-node? true and write to the tenant root, because the runtime
  ;; enters at the root and inheritance runs child -> parent only. So a
  ;; platform leaf is reachable ONLY when a caller resolves through it
  ;; explicitly - which no `:services` reader does.
  ;;
  ;; Kept and documented rather than deleted: it is live in the generic API.
  ;; Do not seed runtime-read values into a platform leaf - that is #275.
  {:platform {:base-tenant-config-key "default"
              :base-label "Default"
              :leaf-tenant-config-key "prod"
              :leaf-label "Prod"}
   :runtime {:base-tenant-config-key "default"
             :base-label "Default"
             :leaf-tenant-config-key "default-runtime"
             :leaf-label "Default Runtime"}
   :dataset {:base-tenant-config-key "default"
             :base-label "Default"
             :leaf-tenant-config-key "default-materialization"
             :leaf-label "Default Materialization"}})

(when-not (= structure/config-roots (set (keys bootstrap-root-defaults)))
  (throw (ex-info "Bootstrap policy must explicitly cover every config root"
                  {:config-roots structure/config-roots
                   :bootstrap-roots (set (keys bootstrap-root-defaults))})))

(defn bootstrap-root-config
  [root]
  (or (get bootstrap-root-defaults root)
      (throw (ex-info "Unsupported bootstrap root"
                      {:root root
                       :supported-roots (sort (keys bootstrap-root-defaults))}))))

(defn make-bootstrap-node-id
  [root tenant tenant-config-key]
  (str (name root) "/" tenant "/" tenant-config-key))

(declare ensure-config-node!
         normalize-runtime-values
         normalize-dataset-values
         normalize-node-values)

(defn- strip-inherit-owned-paths
  "Remove paths whose definition has :config-def/ownership :inherit.
   Tenant trees must never fork inherit-owned paths at bootstrap — those are
   resolved live from the __global__ tenant."
  [db normalized-values]
  (into {}
        (remove (fn [[path _]]
                  (when-let [definition (config-db/get-definition db path)]
                    (= :inherit (:config-def/ownership definition)))))
        normalized-values))

(defn bootstrap-config-tree!
  "Create or update a tenant-local default config tree for any supported root.

   By default this creates a base node plus a leaf node.
   Callers can opt into `single-node?` to seed an explicit-node tree
   with only one node.

   Values whose definition is marked :config-def/ownership :inherit are
   excluded from the seeded tree; they resolve live from the global layer."
  [conn {:keys [root tenant tenant-name created-by
                base-node-id leaf-node-id base-label leaf-label
                base-tenant-config-key leaf-tenant-config-key base-values leaf-values values
                bindings master-key
                single-node?]
         :or {created-by "system"}}]
  (when (str/blank? tenant)
    (throw (ex-info "Missing required tenant" {:root root})))
  (when (seq bindings)
    (throw (ex-info "Legacy binding bootstrap is no longer supported"
                    {:root root
                     :tenant tenant
                     :binding-count (count bindings)})))
  (let [{default-base-tenant-config-key :base-tenant-config-key
         default-base-label :base-label
         default-leaf-tenant-config-key :leaf-tenant-config-key
         default-leaf-label :leaf-label} (bootstrap-root-config root)
        base-tenant-config-key' (or base-tenant-config-key default-base-tenant-config-key)
        leaf-tenant-config-key' (or leaf-tenant-config-key default-leaf-tenant-config-key)
        base-label' (or base-label default-base-label)
        leaf-label' (or leaf-label default-leaf-label)
        base-node-id (or base-node-id (make-bootstrap-node-id root tenant base-tenant-config-key'))
        leaf-node-id (if single-node?
                       base-node-id
                       (or leaf-node-id (make-bootstrap-node-id root tenant leaf-tenant-config-key')))
        _ (when (and (not single-node?)
                     (= base-node-id leaf-node-id))
            (throw (ex-info "Base and leaf node IDs must differ"
                            {:tenant tenant
                             :root root
                             :node-id base-node-id})))
        _ (when (and (not single-node?) (str/blank? leaf-tenant-config-key'))
            ;; Silently creating a leaf with a nil tenant-config-key would make
            ;; it unaddressable - a node holding values that nothing can ask
            ;; for, which is #275 in a new place.
            (throw (ex-info "Non-single-node bootstrap needs a leaf tenant-config-key"
                            {:tenant tenant
                             :root root
                             :hint "this root has no leaf default; pass :leaf-tenant-config-key"})))
        leaf-values' (merge (or values {}) (or leaf-values {}))
        db @conn
        ;; Never strip when the target IS the global tenant — the global tree
        ;; is the home for inherit-owned values.
        strip (if (= tenant config-core/global-tenant)
                identity
                #(strip-inherit-owned-paths db %))
        normalized-base-values (strip (normalize-node-values root base-values))
        normalized-leaf-values (strip (normalize-node-values root leaf-values'))]
    (config-db/register-tenant! conn tenant {:name (or tenant-name tenant)
                                             :created-by created-by})
    (let [base-node (ensure-config-node! conn {:root root
                                               :tenant tenant
                                               :node-id base-node-id
                                               :label base-label'
                                               :tenant-config-key base-tenant-config-key'})
          leaf-node (if single-node?
                      base-node
                      (ensure-config-node! conn {:root root
                                                 :tenant tenant
                                                 :node-id leaf-node-id
                                                 :label leaf-label'
                                                 :tenant-config-key leaf-tenant-config-key'
                                                 :parent-id base-node-id}))
          base-actions (into {}
                             (map (fn [[path value]]
                                    [path (config-db/set-node-value! conn
                                                                     {:root root
                                                                      :tenant tenant
                                                                      :node-id base-node-id
                                                                      :path path
                                                                      :value value
                                                                      :master-key master-key})]))
                             normalized-base-values)
          leaf-actions (into {}
                             (map (fn [[path value]]
                                    [path (config-db/set-node-value! conn
                                                                     {:root root
                                                                      :tenant tenant
                                                                      :node-id (:config.node/id leaf-node)
                                                                      :path path
                                                                      :value value
                                                                      :master-key master-key})]))
                             normalized-leaf-values)]
      {:tenant tenant
       :root root
       :base-node base-node
       :leaf-node leaf-node
       :bindings []
       :value-actions {:base base-actions
                       :leaf leaf-actions}})))

(defn bootstrap-runtime-tree!
  "Create or update a tenant-local default runtime tree for the V2 config model.

   Runtime values may be provided either as canonical skill-property keywords
   like `:rerank-top-k` or as direct `skills.*` path strings."
  [conn {:keys [tenant tenant-name created-by agent-id
                base-node-id runtime-node-id base-label runtime-label
                base-tenant-config-key runtime-tenant-config-key base-values runtime-values values
                master-key
                single-node?]
         :or {created-by "system"
              base-label "Default"
              runtime-label "Default Runtime"
              base-tenant-config-key "default"
              runtime-tenant-config-key "default-runtime"}}]
  (when (str/blank? agent-id)
    (throw (ex-info "Missing required agent-id" {:tenant tenant})))
  (let [{:keys [leaf-node value-actions] :as result}
        (bootstrap-config-tree! conn
                                {:root :runtime
                                 :tenant tenant
                                 :tenant-name tenant-name
                                 :created-by created-by
                                 :single-node? single-node?
                                 :base-node-id base-node-id
                                 :leaf-node-id runtime-node-id
                                 :base-label base-label
                                 :leaf-label runtime-label
                                 :base-tenant-config-key base-tenant-config-key
                                 :leaf-tenant-config-key runtime-tenant-config-key
                                 :base-values base-values
                                 :leaf-values (merge (or values {}) (or runtime-values {}))
                                 :master-key master-key})]
    (assoc result
           :runtime-node leaf-node
           :value-actions {:base (:base value-actions)
                           :runtime (:leaf value-actions)})))

(defn bootstrap-dataset-tree!
  "Create or update a tenant-local default dataset/materialization tree.

   Dataset values may be provided as canonical pipeline-property keywords such as
   `:source-type` or as direct `pipeline.*` path strings."
  [conn {:keys [tenant tenant-name created-by dataset-id pipeline-id
                base-label materialization-label
                base-tenant-config-key base-values shared-values dataset-values values
                master-key]
         :or {created-by "system"
              base-label "Default"
              materialization-label "Default Materialization"
              base-tenant-config-key "default"}}]
  (when (str/blank? dataset-id)
    (throw (ex-info "Missing required dataset-id" {:tenant tenant})))
  (when (str/blank? pipeline-id)
    (throw (ex-info "Missing required pipeline-id" {:tenant tenant
                                                    :dataset-id dataset-id})))
  (let [dataset-config-key (or base-tenant-config-key "default")
        canonical-materialization-tenant-config-key (config-db/default-dataset-tenant-config-key dataset-config-key
                                                                                                 pipeline-id)
        canonical-base-node-id (config-db/dataset-base-node-id tenant dataset-id)
        canonical-materialization-node-id (config-db/dataset-materialization-node-id tenant
                                                                                     dataset-config-key
                                                                                     dataset-id
                                                                                     pipeline-id)
        {:keys [leaf-node value-actions] :as result}
        (bootstrap-config-tree! conn
                                {:root :dataset
                                 :tenant tenant
                                 :tenant-name tenant-name
                                 :created-by created-by
                                 :base-node-id canonical-base-node-id
                                 :leaf-node-id canonical-materialization-node-id
                                 :base-label base-label
                                 :leaf-label materialization-label
                                 :base-tenant-config-key base-tenant-config-key
                                 :leaf-tenant-config-key canonical-materialization-tenant-config-key
                                 :base-values (or shared-values base-values)
                                 :leaf-values (merge (or values {}) (or dataset-values {}))
                                 :master-key master-key})]
    (assoc result
           :materialization-node leaf-node
           :value-actions {:base (:base value-actions)
                           :materialization (:leaf value-actions)})))

(defn ensure-config-node!
  [conn {:keys [root tenant node-id label tenant-config-key parent-id enabled?
                system-managed? created-at updated-at]
         :or {enabled? true}}]
  (if-let [existing (config-db/get-config-node @conn node-id)]
    (do
      (config-db/update-config-node!
       conn
       ;; Forward every field the caller cared about so dump-import can fully
       ;; replace an existing node (including system-managed? + timestamps).
       (cond-> {:node-id node-id
                :label label
                :enabled? enabled?}
         (some? tenant-config-key) (assoc :tenant-config-key tenant-config-key)
         (some? system-managed?)   (assoc :system-managed? system-managed?)
         created-at                (assoc :created-at created-at)
         updated-at                (assoc :updated-at updated-at)))
      (let [current-parent-id (get-in existing [:config.node/parent :config.node/id])]
        (cond
          (and parent-id (not= parent-id current-parent-id))
          (config-db/set-config-node-parent!
           conn
           {:node-id node-id
            :parent-id parent-id})

          (and (nil? parent-id) current-parent-id)
          (d/transact
           conn
           {:tx-data [[:db/retract
                       [:config.node/id node-id]
                       :config.node/parent
                       [:config.node/id current-parent-id]]
                      {:db/id [:config.node/id node-id]
                       :config.node/updated-at (System/currentTimeMillis)}]})))
      (config-db/get-config-node @conn node-id))
    (config-db/create-config-node!
     conn
     ;; Pass through created-at/updated-at + system-managed? so dump-import
     ;; can preserve the original timestamps and management flag on create.
     {:root root
      :tenant tenant
      :node-id node-id
      :label label
      :tenant-config-key tenant-config-key
      :parent-id parent-id
      :enabled? enabled?
      :system-managed? system-managed?
      :created-at created-at
      :updated-at updated-at})))

(defn normalize-runtime-values
  [values]
  (reduce-kv
   (fn [acc k v]
     (if (nil? v)
       acc
       (let [path (cond
                    (string? k) k
                    (keyword? k) (or (config-db/skill-property-path k)
                                     (throw (ex-info "Unknown runtime skill property"
                                                     {:property k})))
                    :else (throw (ex-info "Unsupported runtime value key"
                                          {:key k
                                           :key-type (type k)})))]
         (assoc acc path v))))
   {}
   (or values {})))

(defn normalize-dataset-values
  [values]
  (reduce-kv
   (fn [acc k v]
     (if (nil? v)
       acc
       (cond
         (string? k)
         (assoc acc k v)

         (keyword? k)
         (if-let [path (config-db/pipeline-property-path k)]
           (assoc acc path v)
           acc)

         :else
         (throw (ex-info "Unsupported dataset value key"
                         {:key k
                          :key-type (type k)})))))
   {}
   (or values {})))

(defn normalize-node-values
  [root values]
  (case root
    :runtime (normalize-runtime-values values)
    :dataset (normalize-dataset-values values)
    (reduce-kv
     (fn [acc k v]
       (cond
         (nil? v) acc
         (string? k) (assoc acc k v)
         :else (throw (ex-info "Unsupported config value key for root"
                               {:root root
                                :key k
                                :key-type (type k)}))))
     {}
     (or values {}))))
