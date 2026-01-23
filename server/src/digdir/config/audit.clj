(ns digdir.config.audit
  "Audit logging for configuration changes.

   Records who changed what, when, with before/after values.
   Encrypted values are redacted in audit logs."
  (:require [datahike.api :as d]
            [nano-id.core :refer [nano-id]]
            [digdir.config.crypto :as crypto]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private redacted-text "[REDACTED]")

;; =============================================================================
;; Logging
;; =============================================================================

(defn log-change!
  "Log a configuration change.

   Args:
     conn - Datahike connection
     opts - Map with:
       :path - Config path
       :tenant - Tenant identifier
       :environment - Environment
       :action - :create, :update, or :delete
       :previous-value - Value before change (raw/encrypted)
       :new-value - Value after change (decoded)
       :encrypted? - Whether the value is encrypted
       :user-email - Email of user who made the change
       :user-id - ID of user who made the change
       :ip-address - IP address of requester"
  [conn {:keys [path tenant environment action previous-value new-value
                encrypted? user-email user-id ip-address]}]
  (let [now (System/currentTimeMillis)
        audit-id (nano-id)

        ;; Redact encrypted values for security
        displayed-previous (if encrypted?
                             redacted-text
                             (or previous-value ""))
        displayed-new (if encrypted?
                        redacted-text
                        (pr-str new-value))

        tx-data (cond-> {:audit/id audit-id
                         :audit/timestamp now
                         :audit/action action
                         :audit/config-path path
                         :audit/previous-value (str displayed-previous)
                         :audit/new-value (str displayed-new)}
                  user-email (assoc :audit/user-email user-email)
                  user-id (assoc :audit/user-id user-id)
                  tenant (assoc :audit/tenant tenant)
                  environment (assoc :audit/environment environment)
                  ip-address (assoc :audit/ip-address ip-address)

                  ;; Add references if definition exists
                  true (assoc :audit/config-def [:config-def/path path]))]

    (d/transact conn {:tx-data [tx-data]})
    audit-id))

(defn log-api-key-change!
  "Log an API key operation (create, revoke, update).

   Args:
     conn - Datahike connection
     opts - Map with:
       :action - :create, :revoke, or :update
       :api-key-id - The API key ID
       :api-key-name - Name of the API key
       :entity-id - Associated entity ID
       :previous-entity-id - (for updates) previous entity ID
       :user-email - Email of user performing action
       :user-id - ID of user performing action
       :ip-address - Optional IP address"
  [conn {:keys [action api-key-id api-key-name entity-id
                previous-entity-id user-email user-id ip-address]}]
  (let [now (System/currentTimeMillis)
        audit-id (nano-id)
        tx-data (cond-> {:audit/id audit-id
                         :audit/timestamp now
                         :audit/action action
                         :audit/api-key-id api-key-id
                         :audit/api-key-name api-key-name
                         :audit/api-key-entity-id entity-id}
                  user-email (assoc :audit/user-email user-email)
                  user-id (assoc :audit/user-id user-id)
                  ip-address (assoc :audit/ip-address ip-address)
                  previous-entity-id (assoc :audit/previous-value
                                            (str "entity-id: " previous-entity-id))
                  (and entity-id (= action :update)) (assoc :audit/new-value
                                                            (str "entity-id: " entity-id)))]
    (d/transact conn {:tx-data [tx-data]})
    audit-id))

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
       :environment - Filter by environment
       :limit - Max entries to return (default 100)
       :offset - Entries to skip (default 0)"
  [db path & [{:keys [tenant environment limit offset]
               :or {limit 100 offset 0}}]]
  (let [base-results (d/q '[:find [(pull ?e [*]) ...]
                            :in $ ?path
                            :where
                            [?e :audit/config-path ?path]]
                          db path)
        filtered (cond->> base-results
                   tenant (filter #(= tenant (:audit/tenant %)))
                   environment (filter #(= environment (:audit/environment %))))
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
       :environment - Filter by environment
       :user-email - Filter by user
       :action - Filter by action type (:create, :update, :delete)
       :since - Timestamp to filter changes after
       :limit - Max entries to return (default 100)"
  [db & [{:keys [tenant environment user-email action since limit]
          :or {limit 100}}]]
  (let [base-results (d/q '[:find [(pull ?e [*]) ...]
                            :where [?e :audit/id]]
                          db)
        filtered (cond->> base-results
                   tenant (filter #(= tenant (:audit/tenant %)))
                   environment (filter #(= environment (:audit/environment %)))
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
  [db start-time end-time & [{:keys [tenant environment user-email action limit]
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
                   environment (filter #(= environment (:audit/environment %)))
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
  [db & [{:keys [tenant environment user-email action since]}]]
  (let [base-count (d/q '[:find (count ?e) .
                          :where [?e :audit/id]]
                        db)]
    ;; For filtered counts, we need to query and filter
    (if (or tenant environment user-email action since)
      (count (get-recent-changes db {:tenant tenant
                                     :environment environment
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
     :by-environment (frequencies (map :audit/environment all-changes))}))

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

(comment
  ;; Usage examples:

  ;; Get recent changes
  (get-recent-changes @conn {:limit 10})

  ;; Get history for a specific path
  (get-audit-history @conn "services.azure-openai.api-key")

  ;; Get changes by user
  (get-changes-by-user @conn "admin@digdir.no")

  ;; Get stats
  (get-change-stats @conn {:since (- (System/currentTimeMillis) (* 7 24 60 60 1000))}))
