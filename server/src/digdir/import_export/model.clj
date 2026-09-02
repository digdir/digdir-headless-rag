(ns digdir.import-export.model
  "Canonical import/export model helpers."
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]))

(def system-export-version "2.0")
(def system-export-scope "system")

(def config-export-version "1.0")
(def config-export-scope "full")

(def system-data-keys
  [:definitions
   :nodes
   :bindings
   :compatibilities
   :datasets
   :dataset-pipelines
   :node-values
   :users
   :agents
   :api-keys
   :folders
   :conversations
   :audit])

(defn iso-timestamp
  []
  (.format DateTimeFormatter/ISO_INSTANT (Instant/now)))

(defn system-envelope
  ([data]
   (system-envelope data nil))
  ([data exported-at]
   {:version system-export-version
    :exported-at (or exported-at (iso-timestamp))
    :scope system-export-scope
    :data data}))

(defn config-import-envelope
  [normalized]
  {:version config-export-version
   :scope config-export-scope
   :exported-at (:exported-at normalized)
   :data {:definitions (get-in normalized [:data :definitions])
          :nodes (get-in normalized [:data :nodes])
          :bindings (get-in normalized [:data :bindings])
          :compatibilities (get-in normalized [:data :compatibilities])
          :datasets (get-in normalized [:data :datasets])
          :dataset-pipelines (get-in normalized [:data :dataset-pipelines])
          :node-values (get-in normalized [:data :node-values])
          :audit (get-in normalized [:data :audit])}})

(defn- field
  [m k]
  (or (get m k)
      (get m (name k))))

(defn assert-system-envelope!
  [data]
  (let [version (field data :version)
        scope (field data :scope)
        exported-at (field data :exported-at)
        payload (field data :data)]
    (when-not (map? data)
      (throw (ex-info "System import/export payload must be a map"
                      {:actual-type (type data)})))
    (when-not (= system-export-version version)
      (throw (ex-info "Unsupported system export version"
                      {:expected system-export-version
                       :actual version})))
    (when-not (= system-export-scope scope)
      (throw (ex-info "Unsupported system export scope"
                      {:expected system-export-scope
                       :actual scope})))
    (when-not (string? exported-at)
      (throw (ex-info "System export is missing :exported-at"
                      {:actual exported-at})))
    (when-not (map? payload)
      (throw (ex-info "System export is missing :data"
                      {:actual payload})))
    (let [missing-keys (->> system-data-keys
                            (remove #(contains? payload %))
                            vec
                            seq)
          invalid-keys (->> system-data-keys
                            (filter #(not (sequential? (get payload %))))
                            vec
                            seq)]
      (when missing-keys
        (throw (ex-info "System export is missing required data keys"
                        {:missing-keys missing-keys})))
      (when invalid-keys
        (throw (ex-info "System export contains non-sequential entity data"
                        {:invalid-keys invalid-keys}))))
    data))
