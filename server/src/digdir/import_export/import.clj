(ns digdir.import-export.import
  "System import coordination."
  (:require [digdir.config.env-bridge :as env-bridge]
            [digdir.config.verify :as config-verify]
            [digdir.import-export.files :as files]
            [digdir.import-export.model :as model]
            [digdir.import-export.registry :as registry]
            [digdir.import-export.report :as report]
            [taoensso.telemere :as t]))

(defn- run-system-import-phase
  [mode config-conn main-conn normalized opts]
  (let [on-conflict (or (:on-conflict opts) :skip)
        phase-key (if (= mode :preview) :preview-fn :apply-fn)
        entity-results (reduce (fn [acc {:keys [key] :as entity}]
                                 (assoc acc key
                                        ((phase-key entity) config-conn main-conn normalized opts on-conflict)))
                               {}
                               registry/ordered-system-entities)]
    (report/build-system-import-result mode on-conflict entity-results)))

(defn- normalized-system-export
  [data]
  (model/assert-system-envelope! data))

(defn preview-import-system
  [config-conn main-conn data opts]
  (run-system-import-phase :preview
                           config-conn
                           main-conn
                           (normalized-system-export data)
                           opts))

(defn- imported-platform-tenants
  "Tenants that the import wrote platform config for.

   Nodes live at [:data :nodes] and their root arrives from JSON as a STRING,
   not a keyword - both established by probing the normalized envelope rather
   than assumed. Getting either wrong makes this return an empty list, which
   silently disables the verification below."
  [normalized]
  (->> (get-in normalized [:data :nodes])
       (filter #(contains? #{:platform "platform"} (:config.node/root %)))
       (keep :config.node/tenant)
       (remove #{"__global__"})
       distinct
       vec))

(defn- bridge-env-into-config!
  "Write whatever the environment supplies onto each imported tenant, BEFORE
   the verification below looks.

   Order matters and is the whole point: run after and the report describes a
   system that no longer exists, listing values the operator has in fact
   already supplied. A newcomer's gap is credentials, and a snapshot cannot
   carry those - the ones it does carry are sealed with a master key a fresh
   checkout does not have (#279). This is the step that makes setting
   environment variables sufficient.

   Never fails the import, for the same reason verification does not. Errors
   are collected and returned rather than dropped: a silently skipped
   credential write reproduces the defect the verification exists to catch."
  [config-conn tenants]
  (->> tenants
       (mapv (fn [tenant]
               (try
                 (env-bridge/seed-config-from-env! config-conn tenant)
                 (catch Exception e
                   {:tenant tenant :paths-written [] :actions {}
                    :skipped [{:reason (.getMessage e)}]}))))))

(defn import-system
  "Import a normalized 2.0 system export into fresh or compatible config and main DBs.

   Bridges the environment into the config DB and then verifies that the
   runtime can actually resolve its service config, logging loudly if it
   cannot (#275). A successful import is not evidence of a usable system: the
   shipped snapshot imports cleanly and leaves every `services` value on a node
   the runtime never enters, which previously surfaced several layers
   downstream as an HTTP 400 about a null model name.

   Neither step fails the import - which end should change is an open product
   decision - but neither stays silent either. What the verification reports is
   now a shopping list: the environment variable that supplies each value it
   could not resolve."
  [config-conn main-conn data opts]
  (let [normalized (normalized-system-export data)
        result (run-system-import-phase :apply config-conn main-conn normalized opts)
        tenants (imported-platform-tenants normalized)
        bridged (bridge-env-into-config! config-conn tenants)
        env-paths (vec (distinct (mapcat :paths-written bridged)))
        ;; A write the bridge could not perform is the one thing worse than not
        ;; trying: the variable is set, the operator believes it took, and the
        ;; failure surfaces later as an absent value. Surface it.
        env-skipped (vec (mapcat :skipped bridged))
        ;; `into []`, not a lazy `keep`: this call has EFFECTS (it logs the
        ;; findings) and it must run after the bridge above. A lazy sequence
        ;; runs where it is first realized, not where it is bound, so the
        ;; ordering would have been a property of an unrelated line rather
        ;; than of this one - and reordering these bindings would silently
        ;; keep working until the day something realized it earlier.
        reports (into []
                      (keep (fn [tenant]
                              (try
                                (config-verify/report-unresolved-service-config! @config-conn tenant)
                                (catch Exception _ nil))))
                      tenants)
        unreachable (vec (mapcat :unreachable reports))
        undecryptable (vec (mapcat :undecryptable reports))
        unsupplied (vec (mapcat :unsupplied reports))]
    (when (seq env-skipped)
      (t/log! {:level :error
               :id ::service-config-supplied-but-not-written
               :data {:paths (mapv :path env-skipped)
                      :env-vars (vec (keep :env-var env-skipped))
                      :reasons (vec (distinct (keep :reason env-skipped)))}}
              (str (count env-skipped) " environment-supplied config value(s) could"
                   " NOT be written. The variable is set and the value did not land.")))
    (when (seq env-paths)
      ;; Paths and variable names only. A value must never reach a log line.
      (t/log! {:level :info
               :id ::service-config-supplied-by-environment
               :data {:tenants tenants :paths env-paths}}
              (str (count env-paths) " service config path(s) were supplied by the"
                   " environment and written to the imported tenants.")))
    (cond-> result
      (seq env-paths) (assoc :env-supplied-service-config env-paths)
      (seq unreachable) (assoc :unresolved-service-config unreachable)
      (seq undecryptable) (assoc :undecryptable-service-config undecryptable)
      (seq env-skipped) (assoc :env-supply-skipped env-skipped)
      (seq unsupplied) (assoc :unsupplied-first-query-config unsupplied))))

(defn import-from-file
  [config-conn main-conn file-path opts]
  (let [data (files/read-json-file file-path)]
    (import-system config-conn main-conn data opts)))
