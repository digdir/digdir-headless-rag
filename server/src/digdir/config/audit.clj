(ns digdir.config.audit
  "Audit logging for configuration changes.

   Records who changed what, when, with before/after values.
   Encrypted values are redacted in audit logs."
  (:require [datahike.api :as d]
            [nano-id.core :refer [nano-id]]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private redacted-text "[REDACTED]")

(defn- summarize-api-key-grants
  [{:keys [pipeline-id dataset-scopes agent-refs]}]
  (cond-> {}
    pipeline-id (assoc :pipeline-id pipeline-id)
    (seq dataset-scopes) (assoc :dataset-scopes dataset-scopes)
    (seq agent-refs) (assoc :agent-refs agent-refs)))

;; =============================================================================
;; Logging
;; =============================================================================

(defn change-tx-data
  "Generate transaction data for a configuration change audit record."
  [{:keys [path tenant tenant-config-key action previous-value new-value
           client skill-graph pipeline
           encrypted? user-email user-id ip-address]}]
  (let [now (System/currentTimeMillis)
        audit-id (nano-id)
        displayed-previous (if encrypted? redacted-text (or previous-value ""))
        displayed-new (if encrypted? redacted-text (pr-str new-value))]
    (cond-> {:audit/id audit-id
             :audit/timestamp now
             :audit/action action
             :audit/config-path path
             :audit/previous-value (str displayed-previous)
             :audit/new-value (str displayed-new)}
      user-email (assoc :audit/user-email user-email)
      user-id (assoc :audit/user-id user-id)
      tenant (assoc :audit/tenant tenant)
      tenant-config-key (assoc :audit/tenant-config-key tenant-config-key)
      client (assoc :audit/client client)
      skill-graph (assoc :audit/skill-graph skill-graph)
      pipeline (assoc :audit/pipeline pipeline)
      ip-address (assoc :audit/ip-address ip-address)
      true (assoc :audit/config-def [:config-def/path path]))))

(defn log-change!
  "Log a configuration change. Executes a separate transaction."
  [conn opts]
  (let [tx-data (change-tx-data opts)]
    (d/transact conn {:tx-data [tx-data]})
    (:audit/id tx-data)))

(defn api-key-change-tx-data
  "Generate transaction data for an API key operation audit record."
  [{:keys [action api-key-id api-key-name pipeline-id dataset-scopes agent-refs
           previous-pipeline-id previous-dataset-scopes previous-agent-refs
           user-email user-id ip-address]}]
  (let [now (System/currentTimeMillis)
        audit-id (nano-id)
        previous-summary (summarize-api-key-grants {:pipeline-id previous-pipeline-id
                                                    :dataset-scopes previous-dataset-scopes
                                                    :agent-refs previous-agent-refs})
        current-summary (summarize-api-key-grants {:pipeline-id pipeline-id
                                                   :dataset-scopes dataset-scopes
                                                   :agent-refs agent-refs})]
    (cond-> {:audit/id audit-id
             :audit/timestamp now
             :audit/action action
             :audit/api-key-id api-key-id
             :audit/api-key-name api-key-name}
      pipeline-id (assoc :audit/api-key-pipeline-id pipeline-id)
      user-email (assoc :audit/user-email user-email)
      user-id (assoc :audit/user-id user-id)
      ip-address (assoc :audit/ip-address ip-address)
      (seq previous-summary) (assoc :audit/previous-value (pr-str previous-summary))
      (seq current-summary) (assoc :audit/new-value (pr-str current-summary)))))

(defn log-api-key-change!
  "Log an API key operation. Executes a separate transaction."
  [conn opts]
  (let [tx-data (api-key-change-tx-data opts)]
    (d/transact conn {:tx-data [tx-data]})
    (:audit/id tx-data)))

(defn global-change-tx-data
  "Transaction data for a global-layer change: :global-edit, :pin, or :unpin.

   Opts:
     :action       - :global-edit | :pin | :unpin (required)
     :path         - config path (required)
     :tenant       - tenant ID (required for :pin and :unpin)
     :global-version - global-config version number (required for :global-edit and :pin)
     :changelog    - operator-authored note (for :global-edit)
     :previous-value / :new-value - pr-str'd values (encrypted values are redacted)
     :encrypted?   - redact the displayed values when true
     :user-email / :user-id / :ip-address - actor metadata"
  [{:keys [action path tenant global-version changelog
           previous-value new-value encrypted?
           user-email user-id ip-address]}]
  (let [now (System/currentTimeMillis)
        audit-id (nano-id)
        displayed-previous (if encrypted? redacted-text (or previous-value ""))
        displayed-new (if encrypted? redacted-text (pr-str new-value))]
    (cond-> {:audit/id audit-id
             :audit/timestamp now
             :audit/action action
             :audit/config-path path
             :audit/previous-value (str displayed-previous)
             :audit/new-value (str displayed-new)
             :audit/config-def [:config-def/path path]}
      user-email (assoc :audit/user-email user-email)
      user-id (assoc :audit/user-id user-id)
      tenant (assoc :audit/tenant tenant)
      ip-address (assoc :audit/ip-address ip-address)
      (some? global-version) (assoc :audit/global-version global-version)
      (some? changelog) (assoc :audit/changelog changelog))))

(defn log-global-change!
  "Log a global-edit, pin, or unpin event. Executes a separate transaction."
  [conn opts]
  (let [tx-data (global-change-tx-data opts)]
    (d/transact conn {:tx-data [tx-data]})
    (:audit/id tx-data)))

(defn get-api-key-audit-history
  "Get audit history for a specific API key.

   Args:
     db - Datahike database value
     api-key-id - The API key ID
     opts - Optional filters:
       :limit - Max entries to return (default 100)
       :offset - Entries to skip (default 0)"
  [db api-key-id & [{:keys [limit offset] :or {limit 100 offset 0}}]]
  (let [results (d/q '[:find [(pull ?e [*]) ...]
                       :in $ ?key-id
                       :where
                       [?e :audit/api-key-id ?key-id]]
                     db api-key-id)
        sorted (sort-by :audit/timestamp > results)]
    (->> sorted
         (drop offset)
         (take limit))))

;; =============================================================================
;; Querying
;; =============================================================================

(defn get-audit-log
  "Get audit log entry by ID."
  [db audit-id]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?id
         :where [?e :audit/id ?id]]
       db audit-id))

(defn get-audit-history
  "Get audit history for a config path.

   Args:
     db - Datahike database value
     path - Config path
     opts - Optional filters:
       :tenant - Filter by tenant
       :tenant-config-key - Filter by tenant-config-key
       :limit - Max entries to return (default 100)
       :offset - Entries to skip (default 0)"
  [db path & [{:keys [tenant tenant-config-key limit offset]
               :or {limit 100 offset 0}}]]
  (let [base-results (d/q '[:find [(pull ?e [*]) ...]
                            :in $ ?path
                            :where
                            [?e :audit/config-path ?path]]
                          db path)
        filtered (cond->> base-results
                   tenant (filter #(= tenant (:audit/tenant %)))
                   tenant-config-key (filter #(= tenant-config-key (:audit/tenant-config-key %))))
        sorted (sort-by :audit/timestamp > filtered)]
    (->> sorted
         (drop offset)
         (take limit))))

(defn get-recent-changes
  "Get recent config changes across all paths.

   Args:
     db - Datahike database value
     opts - Optional filters:
       :tenant - Filter by tenant
       :tenant-config-key - Filter by tenant-config-key
       :user-email - Filter by user
       :action - Filter by action type (:create, :update, :delete)
       :since - Timestamp to filter changes after
       :limit - Max entries to return (default 100)"
  [db & [{:keys [tenant tenant-config-key user-email action since limit]
          :or {limit 100}}]]
  (let [base-results (d/q '[:find [(pull ?e [*]) ...]
                            :where [?e :audit/id]]
                          db)
        filtered (cond->> base-results
                   tenant (filter #(= tenant (:audit/tenant %)))
                   tenant-config-key (filter #(= tenant-config-key (:audit/tenant-config-key %)))
                   user-email (filter #(= user-email (:audit/user-email %)))
                   action (filter #(= action (:audit/action %)))
                   since (filter #(> (:audit/timestamp %) since)))
        sorted (sort-by :audit/timestamp > filtered)]
    (take limit sorted)))

(defn get-changes-by-user
  "Get all config changes made by a user.

   Args:
     db - Datahike database value
     user-email - User's email
     opts - Same options as get-recent-changes"
  [db user-email & [opts]]
  (get-recent-changes db (assoc opts :user-email user-email)))

(defn get-changes-for-period
  "Get config changes within a time period.

   Args:
     db - Datahike database value
     start-time - Start timestamp (millis)
     end-time - End timestamp (millis)
     opts - Same filter options as get-recent-changes"
  [db start-time end-time & [{:keys [tenant tenant-config-key user-email action limit]
                              :or {limit 1000}}]]
  (let [base-results (d/q '[:find [(pull ?e [*]) ...]
                            :in $ ?start ?end
                            :where
                            [?e :audit/timestamp ?t]
                            [(>= ?t ?start)]
                            [(<= ?t ?end)]]
                          db start-time end-time)
        filtered (cond->> base-results
                   tenant (filter #(= tenant (:audit/tenant %)))
                   tenant-config-key (filter #(= tenant-config-key (:audit/tenant-config-key %)))
                   user-email (filter #(= user-email (:audit/user-email %)))
                   action (filter #(= action (:audit/action %))))
        sorted (sort-by :audit/timestamp > filtered)]
    (take limit sorted)))

;; =============================================================================
;; Statistics
;; =============================================================================

(defn count-changes
  "Count total number of audit entries.

   Args:
     db - Datahike database value
     opts - Optional filters (same as get-recent-changes)"
  [db & [{:keys [tenant tenant-config-key user-email action since]}]]
  (let [base-count (d/q '[:find (count ?e) .
                          :where [?e :audit/id]]
                        db)]
    ;; For filtered counts, we need to query and filter
    (if (or tenant tenant-config-key user-email action since)
      (count (get-recent-changes db {:tenant tenant
                                     :tenant-config-key tenant-config-key
                                     :user-email user-email
                                     :action action
                                     :since since
                                     :limit 1000000}))
      (or base-count 0))))

(defn get-change-stats
  "Get statistics about config changes.

   Returns: {:total count
             :by-action {:create n, :update n, :delete n}
             :by-user {\"email@example.com\" n, ...}
             :by-tenant {\"ka\" n, ...}}"
  [db & [{:keys [since]}]]
  (let [all-changes (get-recent-changes db {:since since :limit 1000000})]
    {:total (count all-changes)
     :by-action (frequencies (map :audit/action all-changes))
     :by-user (frequencies (map :audit/user-email all-changes))
     :by-tenant (frequencies (map :audit/tenant all-changes))
     :by-tenant-config-key (frequencies (map :audit/tenant-config-key all-changes))}))

(defn distinct-audit-tenants
  "Distinct non-nil tenants present in the audit log, sorted."
  [db]
  (->> (d/q '[:find [?t ...]
              :where [_ :audit/tenant ?t]]
            db)
       (remove nil?)
       sort
       vec))

(defn distinct-audit-tenant-config-keys
  "Distinct non-nil tenant-config-keys present in the audit log, sorted."
  [db]
  (->> (d/q '[:find [?k ...]
              :where [_ :audit/tenant-config-key ?k]]
            db)
       (remove nil?)
       sort
       vec))

;; =============================================================================
;; Cleanup
;; =============================================================================

(defn delete-old-audit-logs!
  "Delete audit logs older than a certain age.

   Args:
     conn - Datahike connection
     max-age-ms - Maximum age in milliseconds (default: 90 days)"
  [conn & [{:keys [max-age-ms] :or {max-age-ms (* 90 24 60 60 1000)}}]]
  (let [cutoff (- (System/currentTimeMillis) max-age-ms)
        old-entries (d/q '[:find [?e ...]
                           :in $ ?cutoff
                           :where
                           [?e :audit/timestamp ?t]
                           [(< ?t ?cutoff)]]
                         @conn cutoff)]
    (when (seq old-entries)
      (d/transact conn {:tx-data (mapv (fn [e] [:db/retractEntity e]) old-entries)}))
    (count old-entries)))
