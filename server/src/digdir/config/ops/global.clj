(ns digdir.config.ops.global
  "Write API for the global (__global__) config layer.

   The global layer is a single shared tree under the sentinel tenant
   core/global-tenant (\"__global__\"). Reads for definitions with
   :config-def/ownership :inherit fall back to this tree when the
   requesting tenant's chain is exhausted.

   Writes here bump a monotonic :config.global/version so operators can track
   which tenants are reading which generation and so tenant pins can reference
   a specific version."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [digdir.config.audit :as audit]
            [digdir.config.core :as core]
            [digdir.config.db :as config-db]))

(def ^:private global-tenant-config-key "default")

(defn- global-node-id
  "Deterministic node ID for the default global node at a root."
  [root]
  (str "__global__/" (name root) "/" global-tenant-config-key))

(defn- ensure-global-tenant-registered!
  [conn]
  (config-db/register-tenant! conn core/global-tenant
                              {:name "Global Defaults"
                               :created-by "system"}))

(defn- global-root-node
  "Read-only lookup of the default global root node for a root; nil if missing."
  [db root]
  (config-db/get-config-node db (global-node-id root)))

(defn ensure-global-root-node!
  "Ensure the default global root node exists for a root. Idempotent."
  [conn root]
  (ensure-global-tenant-registered! conn)
  (let [db @conn
        node-id (global-node-id root)]
    (or (config-db/get-config-node db node-id)
        (config-db/create-config-node! conn
                                       {:root root
                                        :tenant core/global-tenant
                                        :node-id node-id
                                        :label "Global Default"
                                        :tenant-config-key global-tenant-config-key
                                        :system-managed? true}))))

;; =============================================================================
;; Global version tracking
;; =============================================================================

(defn current-global-version
  "Return the highest :config.global/version currently recorded, or 0 if none."
  [db]
  (or (d/q '[:find (max ?v) .
             :where [_ :config.global/version ?v]]
           db)
      0))

(defn get-global-version
  "Fetch the stored metadata for a version number, if present."
  [db version]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?v
         :where [?e :config.global/version ?v]]
       db version))

(defn bump-global-version!
  "Record a new global-config version with changelog metadata. Returns the new
   version number."
  [conn {:keys [changelog created-by]
         :or {created-by "system"}}]
  (when (str/blank? changelog)
    (throw (ex-info "Global-version bump requires a changelog" {})))
  (let [version (inc (current-global-version @conn))
        now (System/currentTimeMillis)]
    (d/transact conn
                {:tx-data [{:config.global/version version
                            :config.global/created-at now
                            :config.global/created-by created-by
                            :config.global/changelog changelog}]})
    version))

;; =============================================================================
;; Global value writes
;; =============================================================================

(defn- assert-inherit-owned!
  [db path]
  (let [definition (or (config-db/get-definition db path)
                       (throw (ex-info "Config definition not found" {:path path})))]
    (when-not (= :inherit (:config-def/ownership definition))
      (throw (ex-info "Global writes require an :inherit-owned definition"
                      {:path path
                       :ownership (or (:config-def/ownership definition) :fork)})))
    definition))

(defn- previous-raw-global-value
  [db root path]
  (when-let [node (global-root-node db root)]
    (some-> (config-db/get-node-value db root core/global-tenant (:config.node/id node) path)
            :config.value/raw)))

(defn set-global-value!
  "Write a value on the global (__global__) tenant's default node.

   Requires the path's definition to declare :config-def/ownership :inherit.
   The write bumps the global version and records a changelog entry.

   Opts:
     :path         - required string
     :value        - value; type checked against the definition
     :root         - a config root (see digdir.config.structure; defaults to the definition's root)
     :master-key   - required for encrypted definitions
     :changelog    - required string explaining the change
     :created-by   - optional actor ID (defaults to \"system\")
     :user-email / :user-id / :ip-address - optional actor metadata for audit

   Returns {:version new-version :action :created|:updated|:unchanged}."
  [conn {:keys [path value root master-key changelog created-by
                user-email user-id ip-address]
         :or {created-by "system"}}]
  (when (str/blank? path)
    (throw (ex-info "Missing required :path" {})))
  (let [db @conn
        definition (assert-inherit-owned! db path)
        resolved-root (or root (:config-def/root definition))
        _ (when (not= resolved-root (:config-def/root definition))
            (throw (ex-info "Global write root mismatch"
                            {:path path
                             :requested-root resolved-root
                             :definition-root (:config-def/root definition)})))
        node (ensure-global-root-node! conn resolved-root)
        previous-raw (previous-raw-global-value db resolved-root path)
        action (config-db/set-node-value! conn
                                          {:root resolved-root
                                           :tenant core/global-tenant
                                           :node-id (:config.node/id node)
                                           :path path
                                           :value value
                                           :master-key master-key})
        version (bump-global-version! conn {:changelog changelog :created-by created-by})]
    (when (not= action :unchanged)
      (audit/log-global-change! conn
                                {:action :global-edit
                                 :path path
                                 :tenant core/global-tenant
                                 :global-version version
                                 :changelog changelog
                                 :previous-value previous-raw
                                 :new-value value
                                 :encrypted? (boolean (:config-def/encrypted? definition))
                                 :user-email (or user-email created-by)
                                 :user-id user-id
                                 :ip-address ip-address}))
    {:version version
     :action action
     :node-id (:config.node/id node)}))

;; =============================================================================
;; Tenant pin / unpin
;; =============================================================================

(defn- resolve-pin-source
  "Read the current effective global value for a path. Returns the raw value
   entity (with the stored :config.value/raw string) or nil when absent."
  [db root path]
  (:value (config-db/resolve-global-value-with-trace db root path)))

(defn pin-tenant-value!
  "Copy the current effective global value for an inherit-owned path into the
   given tenant's node, stamping :config.value/pin-of-version so the pin's
   provenance is traceable.

   Throws if the global tenant has no value for this path."
  [conn {:keys [tenant node-id path root master-key
                user-email user-id ip-address]}]
  (when (str/blank? tenant)
    (throw (ex-info "Missing required :tenant" {})))
  (when (str/blank? node-id)
    (throw (ex-info "Missing required :node-id" {})))
  (let [db @conn
        definition (assert-inherit-owned! db path)
        resolved-root (or root (:config-def/root definition))
        global-value (or (resolve-pin-source db resolved-root path)
                         (throw (ex-info "No global value available to pin"
                                         {:path path :root resolved-root})))
        decoded (config-db/decode-value (:config.value/raw global-value)
                                        (:config-def/value-type definition)
                                        (:config-def/encrypted? definition)
                                        master-key)
        pin-version (current-global-version db)
        action (config-db/set-node-value! conn
                                          {:root resolved-root
                                           :tenant tenant
                                           :node-id node-id
                                           :path path
                                           :value decoded
                                           :master-key master-key})
        value-id (config-db/make-node-value-id resolved-root tenant node-id path)]
    (d/transact conn
                {:tx-data [{:db/id [:config.value/id value-id]
                            :config.value/pin-of-version pin-version
                            :config.value/updated-at (System/currentTimeMillis)}]})
    (audit/log-global-change! conn
                              {:action :pin
                               :path path
                               :tenant tenant
                               :global-version pin-version
                               :previous-value nil
                               :new-value decoded
                               :encrypted? (boolean (:config-def/encrypted? definition))
                               :user-email user-email
                               :user-id user-id
                               :ip-address ip-address})
    {:version pin-version
     :action action
     :pin-of-version pin-version}))

(defn unpin-tenant-value!
  "Retract the tenant-level value and its :pin-of-version stamp so the tenant
   reverts to the live global baseline on next read."
  [conn {:keys [tenant node-id path root
                user-email user-id ip-address]}]
  (when (str/blank? tenant)
    (throw (ex-info "Missing required :tenant" {})))
  (when (str/blank? node-id)
    (throw (ex-info "Missing required :node-id" {})))
  (let [db @conn
        definition (assert-inherit-owned! db path)
        resolved-root (or root (:config-def/root definition))
        previous (some-> (config-db/get-node-value db resolved-root tenant node-id path)
                         :config.value/raw)
        result (config-db/delete-node-value! conn
                                             {:root resolved-root
                                              :tenant tenant
                                              :node-id node-id
                                              :path path})]
    (when result
      (audit/log-global-change! conn
                                {:action :unpin
                                 :path path
                                 :tenant tenant
                                 :previous-value previous
                                 :new-value nil
                                 :encrypted? (boolean (:config-def/encrypted? definition))
                                 :user-email user-email
                                 :user-id user-id
                                 :ip-address ip-address}))
    result))
