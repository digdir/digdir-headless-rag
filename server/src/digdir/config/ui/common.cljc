(ns digdir.config.ui.common
  "Common helper functions and server-side data functions for the V2 config UI."
  (:require [clojure.string :as str]
            #?(:clj [digdir.config.core :as cfg])
            #?(:clj [digdir.config.accessor :as accessor])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.config.structure :as structure])
            #?(:clj [datahike.api :as d])
            #?(:clj [digdir.config.permissions :as perms])
            #?(:clj [digdir.config.ops.clone :as ops-clone])
            #?(:clj [digdir.config.ops.global :as ops-global])
            #?(:clj [digdir.config.ops.ownership :as ops-ownership])
            #?(:clj [digdir.config.ops.retirement :as ops-retirement])
            #?(:clj [digdir.config.ops.sync :as ops-sync])
            #?(:clj [digdir.config.ops.topology :as ops-topology])
            #?(:clj [digdir.pipeline.core :as pipeline])
            #?(:clj [clojure.data.json :as json])
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.edn :as edn])))

;; =============================================================================
;; Formatters & Sanitizers
;; =============================================================================

#?(:clj
   (defn format-timestamp
     "Format an epoch-ms timestamp for display in the Oslo time zone."
     [epoch-ms]
     (when epoch-ms
       (let [inst (java.time.Instant/ofEpochMilli epoch-ms)
             formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
             zoned (.atZone inst (java.time.ZoneId/of "Europe/Oslo"))]
         (.format formatter zoned)))))

(defn mask-secret
  "Mask a secret value for display."
  [value]
  (when value
    (let [len (count (str value))]
      (if (> len 4)
        (str (subs (str value) 0 2) (apply str (repeat (min 8 (- len 4)) "*")) (subs (str value) (- len 2)))
        "****"))))

(defn truncate-value
  "Truncate a value for display in compact cells."
  [value max-len]
  (let [s (str value)]
    (if (> (count s) max-len)
      (str (subs s 0 (- max-len 1)) "...")
      s)))

(defn parse-trace-path-input
  "Parse a textarea-style path list into a stable vector of non-blank config paths."
  [path-input]
  (->> (str/split-lines (or path-input ""))
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

(defn sanitize-tenant-id
  "Normalize tenant IDs to allowed characters, preferring lowercase.
   Allowed characters: a-z, 0-9, and hyphen."
  [s]
  (-> (or s "")
      str/lower-case
      (str/replace #"[^a-z0-9-]+" "-")
      (str/replace #"^-+" "")
      (str/replace #"-+$" "")
      (str/replace #"-{2,}" "-")))

(defn sanitize-config-node-fragment
  "Normalize a human-facing node label/tenant-config-key into a stable node-id fragment."
  [s]
  (let [normalized (-> (or s "")
                       str/lower-case
                       str/trim
                       (str/replace #"[^a-z0-9]+" "-")
                       (str/replace #"^-+" "")
                       (str/replace #"-+$" "")
                       (str/replace #"-{2,}" "-"))]
    (if (str/blank? normalized) "node" normalized)))

(defn valid-tenant-id?
  "Validate tenant ID using allowed characters and shape."
  [tenant-id]
  (boolean (re-matches #"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$" (or tenant-id ""))))

(defn parse-tenant-list-input
  "Parse a textarea or comma-separated tenant list into a stable vector."
  [tenant-input]
  (->> (str/split (or tenant-input "") #"[,\n\r]+")
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

;; =============================================================================
;; UI Label Helpers
;; =============================================================================

(defn binding-types-for-root
  "Bindings are deprecated. The diagnostics UI no longer offers binding creation."
  [_root]
  [])

(defn parent-preview-label
  "Describe a pending parent change for display in the tree inspector."
  [current-parent-id target-parent-id]
  (cond
    (= (or current-parent-id "") (or target-parent-id "")) nil
    (str/blank? target-parent-id) (str "Preview: node will move from " (or current-parent-id "root") " to root")
    :else (str "Preview: node will move from " (or current-parent-id "root") " to " target-parent-id)))

(defn preview-display-value
  "Render a compact preview value for semantic dry-run output."
  [value]
  (if (nil? value)
    "<unset>"
    (truncate-value (pr-str value) 80)))

(defn node-delete-preview-label
  "Describe what a node deletion will remove."
  [node]
  (str "Delete preview: removes "
       (count (:bindings node)) " binding(s), and "
       (count (:values node)) " direct value(s)."))

(defn node-delete-preview-lines
  "Describe the concrete bindings and direct values removed by node deletion."
  [node]
  (vec
   (concat
    (map (fn [binding]
           (str "Binding: " (name (:type binding)) " = " (:value binding)))
         (:bindings node))
    (map (fn [value]
           (str "Direct value: " (:path value) " = " (:display-value value)))
         (:values node)))))

(defn binding-delete-preview-label
  "Describe the concrete binding removed by a delete-binding mutation."
  [{:keys [binding-id type value node-id]}]
  (str "Delete preview: removes "
       (name type) " = " value
       " from " node-id
       " (" binding-id ")."))

(defn value-reset-preview-label
  "Describe the concrete direct value removed by a reset mutation."
  [{:keys [path display-value node-id]}]
  (str "Reset preview: removes the direct override "
       path " = " display-value
       " from " node-id
       "; this node will inherit or become unset."))

(defn value-reset-impact-summary-label
  "Summarize the effective value after a direct node value reset."
  [{:keys [after-value after-winning-node after-stop-reason]}]
  (case after-stop-reason
    :matched
    (str "After reset: "
         after-value
         " from "
         (or after-winning-node "unknown") ".")

    :disabled-node
    "After reset: resolution would stop at a disabled ancestor."

    :root-exhausted
    "After reset: this path would become unset."

    nil))

(defn node-dirty-fields
  "Return the mutable node fields that differ from the persisted node."
  [node draft-label draft-tenant-config-key draft-enabled draft-parent-id]
  (let [fields []
        fields (cond-> fields
                 (not= (:label node) draft-label) (conj :label)
                 (not= (:tenant-config-key node) draft-tenant-config-key) (conj :tenant-config-key)
                 (not= (boolean (:enabled? node)) (= "enabled" draft-enabled)) (conj :enabled)
                 (not= (or (:parent-id node) "") (or draft-parent-id "")) (conj :parent))]
    fields))

(defn dirty-fields-label
  "Render a compact unsaved-changes label from changed field keywords."
  [fields]
  (when (seq fields)
    (str "Unsaved changes: "
         (str/join ", " (map name fields)))))

(def ^:private soft-depth-warning-threshold 4)

(defn depth-warning-label
  "Render a soft warning when a node sits deeper than the recommended depth."
  [depth]
  (when (>= (or depth 0) soft-depth-warning-threshold)
    (str "Depth warning: depth " depth
         " is harder to reason about than the recommended maximum of "
         soft-depth-warning-threshold ".")))

(defn effective-disabled-label
  "Describe why a node is effectively disabled."
  [{:keys [enabled? effectively-disabled? disabled-by-node-id node-id]}]
  (cond
    (not enabled?) (str "Disabled at this node (" node-id ").")
    effectively-disabled? (str "Effectively disabled by ancestor " disabled-by-node-id ".")
    :else nil))

(defn reparent-impact-summary-label
  "Summarize a semantic reparent preview for display on a node card."
  [{:keys [changed-path-count changed-value-count changed-winning-node-count]}]
  (when (some? changed-path-count)
    (if (zero? changed-path-count)
      "Effective config preview: no resolved paths would change."
      (str "Effective config preview: "
           changed-path-count " path(s) would change, "
           changed-value-count " with different values and "
           changed-winning-node-count " with a different winning node."))))

(defn selector-binding-type?
  "Return true when a binding type selects a node directly."
  [binding-type]
  (contains? #{:platform-profile :runtime-profile :dataset-profile
               :agent :dataset :pipeline} binding-type))

(defn binding-preview-summary-label
  "Summarize the semantic effect of creating legacy binding metadata."
  [{:keys [binding-kind resolved-path-count]}]
  (when (some? resolved-path-count)
    (case binding-kind
      :selector
      (if (zero? resolved-path-count)
        "Binding preview: selector would target this node, but no rooted values are currently resolved."
        (str "Binding preview: selector would target this node and resolve "
             resolved-path-count " path(s)."))

      nil)))

(defn binding-preview-note-label
  "Describe how a pending binding participates as metadata."
  [{:keys [binding-kind binding-type binding-value node-id]}]
  (when (and binding-kind binding-type node-id)
    (case binding-kind
      :selector
      (str "Binding " (name binding-type) " '" binding-value
           "' would map to " node-id
           " as legacy selector metadata; request-time callers still choose explicit node tenant-config-keys.")

      nil)))

(defn value-editor-status-label
  "Return an inline status label for the node value editor."
  [value-path selected-definition]
  (cond
    (str/blank? value-path) "Enter a path to edit a direct node value."
    (nil? selected-definition) "Unknown path for this root."
    :else (str "Ready to set "
               value-path
               " as "
               (:value-type selected-definition)
               (when (:encrypted? selected-definition) ", encrypted")
               (when (:multiline? selected-definition) ", multiline"))))

#?(:clj
   (defn get-master-key
     "Expose the configured master key to shared Electric UI code."
     []
     (cfg/get-master-key))
   :cljs
   (defn get-master-key [& _]
     (throw (ex-info "Master key access is only available on the JVM server" {}))))

#?(:clj
   (defn decode-node-value
     "Decode a config.value entity using the corresponding definition metadata."
     [value definition master-key]
     (config-db/decode-value (:config.value/raw value)
                             (:config-def/value-type definition)
                             (:config-def/encrypted? definition)
                             master-key))
   :cljs
   (defn decode-node-value [& _]
     (throw (ex-info "Node value decoding is only available on the JVM server" {}))))

;; =============================================================================
;; Config Logic & ID Generation
;; =============================================================================

(defn suggest-config-node-id
  "Build a stable node-id suggestion from root, tenant, label/tenant-config-key, and existing nodes."
  [root tenant label tenant-config-key existing-node-ids]
  (let [base-fragment (sanitize-config-node-fragment (or (not-empty tenant-config-key) label))
        base-id (str (name root) "/" tenant "/" base-fragment)]
    (if-not (contains? existing-node-ids base-id)
      base-id
      (loop [n 2]
        (let [candidate (str base-id "-" n)]
          (if (contains? existing-node-ids candidate)
            (recur (inc n))
            candidate))))))

(defn config-node-option-label
  "Render a UI label for a config node option without forcing the user to know the raw ID."
  [node]
  (let [label (or (:label node) (:config.node/label node))
        tenant-config-key (or (:tenant-config-key node) (:config.node/tenant-config-key node))
        node-id (or (:node-id node) (:config.node/id node))
        display-name (cond
                       (and label tenant-config-key (not= label tenant-config-key)) (str label " [" tenant-config-key "]")
                       tenant-config-key tenant-config-key
                       :else label)]
    (if (and display-name (not= display-name node-id))
      (str display-name " (" node-id ")")
      node-id)))

(defn group-paths-by-segment
  "Group config definitions by their first path segment."
  [definitions]
  (reduce
   (fn [acc def]
     (let [path (:config-def/path def)
           first-segment (first (str/split path #"\." 2))]
       (update acc first-segment (fnil conj []) def)))
   (sorted-map)
   definitions))

;; =============================================================================
;; Server-side Data Functions
;; =============================================================================

#?(:clj
   (defn trace-stop-reason-label
     "Convert an internal trace stop reason into UI-facing text."
     [stop-reason]
     (case stop-reason
       nil "Resolved"
       :definition-not-found "Definition not found"
       :disabled-node "Traversal stopped at disabled node"
       :value-not-found "No value found"
       (-> stop-reason name (str/replace "-" " ") str/capitalize))))

#?(:clj
   (defn format-runtime-trace
     "Normalize a runtime trace map into a UI-friendly diagnostic summary."
     [path trace]
     (let [selected-node (:selected-node trace)
           winning-node (:winning-node trace)
           stopped-at (:stopped-at trace)
           traversal-path (vec (or (:traversal-path trace) []))]
       {:path path
        :selected-root (:selected-root trace)
        :selected-tenant (:selected-tenant trace)
        :selected-node selected-node
        :winning-node winning-node
        :stopped-at stopped-at
        :resolved? (some? winning-node)
        :decoded-value (:decoded-value trace)
        :stop-reason (:stop-reason trace)
        :stop-label (trace-stop-reason-label (:stop-reason trace))
        :path-nodes (mapv (fn [node-id]
                            {:node-id node-id
                             :selected? (= node-id selected-node)
                             :winning? (= node-id winning-node)
                             :stopped? (= node-id stopped-at)})
                          traversal-path)})))

#?(:clj
   (defn- annotate-config-tree-nodes
     "Add derived depth and effective-enabled state for diagnostics display."
     [nodes]
     (let [nodes-by-id (into {} (map (juxt :node-id identity)) nodes)
           !cache (atom {})]
       (letfn [(annotation-for [node-id]
                 (if-let [cached (get @!cache node-id)]
                   cached
                   (let [{:keys [parent-id enabled?]} (get nodes-by-id node-id)
                         annotation (if-let [parent-node-id parent-id]
                                      (let [{:keys [depth effectively-disabled? disabled-by-node-id]}
                                            (annotation-for parent-node-id)
                                            inherited-disabled-id (when effectively-disabled?
                                                                    disabled-by-node-id)
                                            disabled-by (cond
                                                          (not enabled?) node-id
                                                          inherited-disabled-id inherited-disabled-id
                                                          :else nil)
                                            depth' (inc depth)]
                                        {:depth depth'
                                         :effectively-disabled? (some? disabled-by)
                                         :disabled-by-node-id disabled-by
                                         :depth-warning (depth-warning-label depth')})
                                      (let [disabled-by (when-not enabled? node-id)]
                                        {:depth 0
                                         :effectively-disabled? (some? disabled-by)
                                         :disabled-by-node-id disabled-by
                                         :depth-warning nil}))]
                     (swap! !cache assoc node-id annotation)
                     annotation)))]
         (mapv (fn [node]
                 (merge node (annotation-for (:node-id node))))
               nodes)))))

#?(:clj
   (defn build-config-tree-diagnostics
     "Combine nodes, bindings, and optional dataset catalog into a UI-friendly tree summary."
     [root tenant nodes bindings {:keys [db datasets pipelines definitions node-values-by-node master-key]}]
     (let [bindings-by-node (group-by #(get-in % [:config.binding/node :config.node/id]) bindings)
           normalized-nodes (->> (sort-by :config.node/id nodes)
                                 (mapv (fn [node]
                                         (let [node-id (:config.node/id node)
                                               node-values (vec (sort-by #(get-in % [:config.value/definition :config-def/path])
                                                                         (get node-values-by-node node-id [])))
                                               node-bindings (vec (sort-by (juxt :config.binding/type :config.binding/value)
                                                                           (get bindings-by-node node-id [])))]
                                           {:node-id node-id
                                            :label (:config.node/label node)
                                            :tenant-config-key (:config.node/tenant-config-key node)
                                            :enabled? (:config.node/enabled? node)
                                            :parent-id (get-in node [:config.node/parent :config.node/id])
                                            :binding-count (count node-bindings)
                                            :value-count (count node-values)
                                            :bindings (mapv (fn [binding]
                                                              {:binding-id (:config.binding/id binding)
                                                               :type (:config.binding/type binding)
                                                               :value (:config.binding/value binding)})
                                                            node-bindings)
                                            :values (mapv (fn [value]
                                                            (let [definition (:config.value/definition value)
                                                                  encrypted? (:config-def/encrypted? definition)
                                                                  decoded (try
                                                                            (config-db/decode-value
                                                                             (:config.value/raw value)
                                                                             (:config-def/value-type definition)
                                                                             encrypted?
                                                                             master-key)
                                                                            (catch Exception _
                                                                              nil))
                                                                  display-value (cond
                                                                                  encrypted? (if (some? decoded)
                                                                                               (mask-secret decoded)
                                                                                               "<encrypted>")
                                                                                  (some? decoded) (truncate-value (pr-str decoded) 80)
                                                                                  :else "<unavailable>")]
                                                              {:path (:config-def/path definition)
                                                               :value-type (:config-def/value-type definition)
                                                               :encrypted? encrypted?
                                                               :display-value display-value}))
                                                          node-values)})))
                                 annotate-config-tree-nodes)
           normalized-definitions (mapv (fn [definition]
                                          {:path (:config-def/path definition)
                                           :value-type (:config-def/value-type definition)
                                           :encrypted? (:config-def/encrypted? definition)
                                           :multiline? (:config-def/multiline? definition)})
                                        (sort-by :config-def/path (or definitions [])))
           dataset-catalog (when (= :dataset root)
                             ;; Diagnostics is scoped to a single tenant — restrict
                             ;; the shared materialization-context index to nodes
                             ;; in that tenant before resolving each pipeline so
                             ;; we keep the per-tenant view but skip the per-call
                             ;; tenant×nodes inference scan.
                             (let [contexts-for-tenant (when db
                                                         (->> (config-db/materialization-contexts-by-pipeline-id db)
                                                              (reduce-kv (fn [acc pid ctxs]
                                                                           (let [scoped (filterv #(= tenant (:tenant %)) ctxs)]
                                                                             (cond-> acc
                                                                               (seq scoped) (assoc pid scoped))))
                                                                         {})))]
                               {:datasets (mapv (fn [dataset]
                                                  {:dataset-id (:dataset/id dataset)
                                                   :name (:dataset/name dataset)
                                                   :enabled? (:dataset/enabled? dataset)})
                                                (or datasets []))
                                :pipelines (mapv (fn [pipeline]
                                                   (let [pipeline-id (:dataset.pipeline/id pipeline)
                                                         contexts (when contexts-for-tenant
                                                                    (get contexts-for-tenant pipeline-id))
                                                         effective-pipeline (if db
                                                                              (config-db/effective-dataset-pipeline-record
                                                                               db
                                                                               pipeline
                                                                               (cond-> {:master-key master-key
                                                                                        :tenant tenant}
                                                                                 contexts (assoc :contexts contexts)))
                                                                              pipeline)]
                                                     (cond-> {:pipeline-id pipeline-id
                                                              :name (:dataset.pipeline/effective-name effective-pipeline)
                                                              :dataset-id (get-in effective-pipeline [:dataset.pipeline/dataset :dataset/id])
                                                              :enabled? (:dataset.pipeline/enabled? effective-pipeline)}
                                                       (:dataset.pipeline/effective-name-ambiguous? effective-pipeline)
                                                       (assoc :name-ambiguous? true))))
                                                 (or pipelines []))}))]
       {:root root
        :tenant tenant
        :node-count (count normalized-nodes)
        :definition-count (count normalized-definitions)
        :binding-count (count bindings)
        :definitions normalized-definitions
        :nodes normalized-nodes
        :dataset-catalog dataset-catalog}))
   :cljs
   (defn build-config-tree-diagnostics [& _]
     (throw (ex-info "Config tree diagnostics are only available on the JVM server" {}))))

#?(:clj
   (defn get-runtime-trace-data
     "Load runtime V2 config traces in a UI-friendly shape.
      Returns {:status :success :data ...} or {:status :error :error message}."
     [{:keys [tenant node-id tenant-config-key agent-id dataset-id paths] :as _opts}]
     (try
       (let [conn (config-db/get-conn)]
         (if (nil? conn)
           {:status :error :error "No database connection"}
           (let [path-strs (vec paths)
                 _ (when (empty? path-strs)
                     (throw (ex-info "At least one runtime path is required" {})))
                 {:keys [config traces node]} (accessor/load-runtime-config-v2-with-trace
                                               {:tenant tenant
                                                :node-id node-id
                                                :tenant-config-key tenant-config-key
                                                :agent-id agent-id
                                                :dataset-id dataset-id
                                                :paths path-strs})]
             {:status :success
             :data {:selected-node {:id (:config.node/id node)
                                     :label (:config.node/label node)}
                     :config config
                     :traces (mapv #(format-runtime-trace % (get traces %)) path-strs)}})))
       (catch Exception e
         {:status :error :error (or (ex-message e) "Trace lookup failed")})))
   :cljs
   (defn get-runtime-trace-data [& _]
     (throw (ex-info "Runtime trace diagnostics are only available on the JVM server" {}))))

#?(:clj
   (defn get-binding-preview-data
     "Bindings are deprecated and no longer supported by the diagnostics UI."
     [_opts]
     {:status :error
      :error "Binding metadata is deprecated. Use node tenant-config-keys directly."})
   :cljs
   (defn get-binding-preview-data [& _]
     (throw (ex-info "Binding preview is only available on the JVM server" {}))))

#?(:clj
   (defn mutation-success-label
     "Build a stable user-facing success message for a tree mutation."
     [{:keys [op node-id parent-id path binding-id]}]
     (case op
       :create-node (str "Created node " node-id)
       :update-node (str "Updated node " node-id)
       :set-node-parent (str "Reparented " node-id " to " parent-id)
       :clear-node-parent (str "Cleared parent for " node-id)
       :delete-node (str "Deleted node " node-id)
       :create-binding (str "Created binding for " node-id)
       :delete-binding (str "Deleted binding " binding-id)
       :set-node-value (str "Set " path " on " node-id)
       :delete-node-value (str "Reset " path " on " node-id)
       "Tree mutation completed")))

#?(:clj
   (defn parse-value-by-type
     "Safely parse a string value based on its type.
      Returns the parsed value or throws an exception for invalid input."
     [value value-type]
     (case value-type
       :string value
       :boolean (case (str/lower-case (str/trim (or value "")))
                  ("true" "1" "yes") true
                  ("false" "0" "no" "") false
                  (throw (ex-info "Invalid boolean value" {:value value})))
       :number (let [trimmed (str/trim value)]
                 (if (str/includes? trimmed ".")
                   (Double/parseDouble trimmed)
                   (Long/parseLong trimmed)))
       :edn (edn/read-string {:readers {}} value)
       ;; Default to string for unknown types
       value)))

#?(:clj
   (defn- ensure-config-ui-admin!
     [db user-id]
     (when-not (perms/is-admin? db user-id)
       (throw (ex-info "Permission denied - admin required" {})))))

#?(:clj
   (def ^:private tree-snapshot-mutation-ops
     #{:delete-node :set-node-parent :clear-node-parent}))

#?(:clj
   (defn- sanitize-snapshot-token
     [value]
     (-> (or value "config-tree")
         str
         (str/replace #"[^A-Za-z0-9._-]+" "-")
         (str/replace #"^-+|-+$" ""))))

#?(:clj
   (defn- maybe-export-tree-mutation-snapshot!
     [conn {:keys [tenant root op node-id]}]
     (when (and (contains? tree-snapshot-mutation-ops op)
                (not (str/blank? tenant))
                root)
       (let [snapshot-file (io/file "server"
                                    "state"
                                    "config-tree-snapshots"
                                    (sanitize-snapshot-token tenant)
                                    (str (System/currentTimeMillis)
                                         "-" (name root)
                                         "-" (name op)
                                         "-" (sanitize-snapshot-token node-id)
                                         ".json"))]
         (io/make-parents snapshot-file)
         (ops-sync/export-to-file conn (.getPath snapshot-file) {:tenant tenant
                                                            :include-audit? false})
         (.getPath snapshot-file)))))

#?(:clj
   (defn mutate-config-tree!
     "Perform a root-local config tree mutation for the admin diagnostics UI."
     [{:keys [op tenant root node-id label tenant-config-key parent-id enabled?
              _binding-id binding-type binding-value
              path raw-value user-id changelog
              candidate-value non-matching-strategy]}]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             master-key (cfg/get-master-key)
             normalize-blank #(let [s (some-> % str str/trim)]
                                (when-not (str/blank? s) s))]
         (ensure-config-ui-admin! db user-id)
         (maybe-export-tree-mutation-snapshot! conn {:tenant tenant
                                                     :root root
                                                     :op op
                                                     :node-id node-id})
         (let [result (case op
                        :create-node
                        (do (config-db/create-config-node!
                             conn
                             {:root root
                              :tenant tenant
                              :node-id node-id
                              :label label
                              :tenant-config-key (normalize-blank tenant-config-key)
                              :parent-id (normalize-blank parent-id)
                              :enabled? (if (nil? enabled?) true enabled?)})
                            :ok)

                        :update-node
                        (do (config-db/update-config-node!
                             conn
                             (cond-> {:node-id node-id}
                               (contains? #{true false} enabled?) (assoc :enabled? enabled?)
                               (some? label) (assoc :label label)
                               (some? tenant-config-key) (assoc :tenant-config-key (normalize-blank tenant-config-key))))
                            :ok)

                        :set-node-parent
                        (do (config-db/set-config-node-parent!
                             conn
                             {:node-id node-id
                              :parent-id parent-id})
                            :ok)

                        :clear-node-parent
                        (do (config-db/clear-config-node-parent!
                             conn
                             {:node-id node-id})
                            :ok)

                        :delete-node
                        (do (config-db/delete-config-node! conn {:root root
                                                                :tenant tenant
                                                                :node-id node-id})
                            :ok)

                        :create-binding
                        (throw (ex-info "Binding metadata is deprecated"
                                        {:root root
                                         :tenant tenant
                                         :node-id node-id
                                         :binding-type binding-type
                                         :binding-value binding-value}))

                        :set-node-value
                        (let [definition (or (config-db/get-definition db path)
                                             (throw (ex-info "Config definition not found" {:path path})))
                              parsed-value (parse-value-by-type raw-value (:config-def/value-type definition))]
                          (config-db/set-node-value!
                           conn
                           {:root root
                            :tenant tenant
                            :node-id node-id
                            :path path
                            :value parsed-value
                            :master-key master-key})
                          :ok)

                        :delete-node-value
                        (do (config-db/delete-node-value!
                             conn
                             {:root root
                              :tenant tenant
                              :node-id node-id
                              :path path})
                            :ok)

                        :set-global-value
                        (let [definition (or (config-db/get-definition db path)
                                             (throw (ex-info "Config definition not found" {:path path})))
                              parsed-value (parse-value-by-type raw-value (:config-def/value-type definition))
                              changelog' (or (normalize-blank changelog)
                                             (throw (ex-info "Missing required :changelog" {:path path})))]
                          (ops-global/set-global-value!
                           conn
                           {:path path
                            :value parsed-value
                            :changelog changelog'
                            :master-key master-key
                            :created-by (or user-id "operator")
                            :user-id user-id})
                          :ok)

                        :pin-tenant-value
                        (do (ops-global/pin-tenant-value!
                             conn
                             {:tenant tenant
                              :node-id node-id
                              :path path
                              :master-key master-key
                              :user-id user-id})
                            :ok)

                        :unpin-tenant-value
                        (do (ops-global/unpin-tenant-value!
                             conn
                             {:tenant tenant
                              :node-id node-id
                              :path path
                              :user-id user-id})
                            :ok)

                        :promote-to-global
                        (let [definition (or (config-db/get-definition db path)
                                             (throw (ex-info "Config definition not found"
                                                             {:path path})))
                              parsed-candidate (if (string? raw-value)
                                                 (parse-value-by-type raw-value
                                                                      (:config-def/value-type definition))
                                                 candidate-value)
                              strategy (or non-matching-strategy :pin-all)
                              changelog' (or (normalize-blank changelog)
                                             (throw (ex-info "Promote requires a :changelog"
                                                             {:path path})))]
                          (ops-ownership/promote-to-global!
                           conn
                           {:path path
                            :candidate-value parsed-candidate
                            :non-matching-strategy strategy
                            :changelog changelog'
                            :master-key master-key
                            :user-id user-id}))

                        :demote-from-global
                        (ops-ownership/demote-from-global!
                         conn
                         {:path path
                          :master-key master-key
                          :user-id user-id})

                        :pin-all-globals-for-tenant
                        (ops-ownership/pin-all-globals-for-tenant!
                         conn
                         {:tenant tenant
                          :root root
                          :master-key master-key
                          :user-id user-id})

                        (throw (ex-info "Unsupported config tree mutation"
                                        {:op op})))]
           result))))
   :cljs
   (defn mutate-config-tree! [& _]
     (throw (ex-info "Config tree mutations are only available on the JVM server" {}))))

#?(:clj
   (defn create-config-tree-binding!
     "Create a config binding through the Operator Console diagnostics surface."
     [_tenant _root _node-id _binding-type _binding-value _user-id]
     (throw (ex-info "Binding metadata is deprecated" {})))
   :cljs
   (defn create-config-tree-binding! [& _]
     (throw (ex-info "Config tree binding creation is only available on the JVM server" {}))))

#?(:clj
   (defn create-dataset-handler!
     "Create a new durable parent dataset for operator workflows."
     [name description user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (pipeline/create-dataset! conn (cond-> {:name name}
                                          (some? description) (assoc :description description))))))
   :cljs
   (defn create-dataset-handler! [& _]
     (throw (ex-info "Dataset creation is only available on the JVM server" {}))))

#?(:clj
   (defn create-pipeline-handler!
     "Create a new pipeline."
     [tenant dataset-id pipeline-id properties user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             master-key (cfg/get-master-key)]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (pipeline/create-pipeline! conn {:tenant tenant
                                          :environment nil
                                          :dataset-id dataset-id
                                          :pipeline-name pipeline-id
                                          :properties properties
                                          :master-key master-key})
         :ok))))

#?(:clj
   (defn create-tenant-handler!
     "Create a new tenant with ID and display name."
     [tenant-id tenant-name user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             normalized-id (sanitize-tenant-id (str/trim (or tenant-id "")))
             normalized-name (str/trim (or tenant-name ""))]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (when (str/blank? normalized-name)
           (throw (ex-info "Tenant name is required" {})))
         (when (str/blank? normalized-id)
           (throw (ex-info "Tenant ID is required" {})))
         (when-not (valid-tenant-id? normalized-id)
           (throw (ex-info "Tenant ID must use lowercase letters, numbers, and hyphens" {})))
         (when (config-db/get-tenant db normalized-id)
           (throw (ex-info "Tenant already exists" {:tenant-id normalized-id})))
         (config-db/register-tenant! conn normalized-id {:name normalized-name
                                                         :created-by user-id})
         :ok))))

#?(:clj
   (defn duplicate-pipeline-handler!
     "Duplicate an existing pipeline."
     [tenant source-pipeline-id new-pipeline-id user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             master-key (cfg/get-master-key)]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (pipeline/duplicate-pipeline! conn {:tenant tenant
                                             :environment nil
                                             :source-pipeline-name source-pipeline-id
                                             :new-pipeline-name new-pipeline-id
                                             :master-key master-key})
         :ok))))

#?(:clj
   (defn soft-delete-pipeline-handler!
     "Soft-delete an pipeline."
     [tenant pipeline-id user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (pipeline/soft-delete-pipeline! conn tenant nil pipeline-id)
         :ok))))

;; =============================================================================
;; Operations Handlers (Server-side)
;; =============================================================================

#?(:clj
   (defn- with-admin-conn
     "Execute body-fn with an admin-verified conn, db, and master-key.
      Returns {:status :success :data result} or {:status :error :error message}.
      body-fn receives {:keys [conn db master-key]}."
     [user-id body-fn]
     (try
       (if-let [conn (config-db/get-conn)]
         (let [db @conn]
           (if-not (perms/is-admin? db user-id)
             {:status :error :error "Permission denied - admin required"}
             {:status :success
              :data (body-fn {:conn conn :db db :master-key (cfg/get-master-key)})}))
         {:status :error :error "No database connection"})
       (catch Exception e
         {:status :error :error (or (ex-message e) "Operation failed")}))))

#?(:clj
   (defn- resolve-inheritance-selected-node
     [db root tenant {:keys [node-id tenant-config-key]}]
     (cond
       (seq node-id)
       (let [node (or (config-db/get-config-node db node-id)
                      (throw (ex-info "Config node not found"
                                      {:node-id node-id})))]
         (when (or (not= root (:config.node/root node))
                   (not= tenant (:config.node/tenant node)))
           (throw (ex-info "Config node does not belong to the selected root and tenant"
                           {:node-id node-id
                            :root root
                            :tenant tenant})))
         node)

       (seq tenant-config-key)
       (or (config-db/get-config-node-by-tenant-config-key db tenant root tenant-config-key)
           (throw (ex-info (str (name root) " config node not found")
                           {:tenant tenant
                            :root root
                            :tenant-config-key tenant-config-key})))

       :else
       nil)))

#?(:clj
   (defn- fetch-values-by-node
     [db root tenant chain]
     (when (seq chain)
       (let [node-eids (keep #(config-db/config-node-eid db (:config.node/id %)) chain)]
         (->> (d/q '[:find ?node-id ?path (pull ?e [*])
                     :in $ [?node-eid ...] ?root ?tenant
                     :where
                     [?e :config.value/node ?node-eid]
                     [?node-eid :config.node/id ?node-id]
                     [?node-eid :config.node/root ?root]
                     [?node-eid :config.node/tenant ?tenant]
                     [?e :config.value/definition ?def]
                     [?def :config-def/path ?path]
                     (not [?e :config.value/deleted-at])]
                   db node-eids root tenant)
              (reduce (fn [acc [node-id path value]]
                        (assoc-in acc [node-id path] value))
                      {}))))))

#?(:clj
   (defn fetch-global-layer-data
     "Fetch the global (__global__) tenant's chain and values for a root.
      Returns {:chain [...] :values-by-node {node-id {path value}} :root-node ...}
      or nil when the global tenant has no tree at this root."
     [db root]
     (when-let [root-node (config-db/get-config-node-by-tenant-config-key
                           db cfg/global-tenant root "default")]
       (let [chain (loop [node root-node, acc []]
                     (if-not node
                       acc
                       (recur (when-let [pid (get-in node [:config.node/parent :config.node/id])]
                                (config-db/get-config-node db pid))
                              (conj acc node))))
             values-by-node (fetch-values-by-node db root cfg/global-tenant chain)]
         {:chain chain
          :values-by-node values-by-node
          :root-node root-node}))))

#?(:clj
   (defn get-inheritance-editor-data
     [{:keys [root tenant node-id tenant-config-key]}]
     (let [conn (config-db/get-conn)
           db @conn
           definitions (config-db/get-definitions-by-root db root)
           paths (mapv :config-def/path definitions)
           selected-node (resolve-inheritance-selected-node db root tenant
                                                            {:node-id node-id
                                                             :tenant-config-key tenant-config-key})
           selected-node-id (:config.node/id selected-node)
           resolution (if (and selected-node-id (seq paths))
                        (config-db/resolve-node-values-batch db root tenant selected-node-id paths)
                        {:results {} :chain []})
           chain (:chain resolution)
           values-by-node (fetch-values-by-node db root tenant chain)
           nodes (config-db/list-config-nodes db tenant root)
           global-layer (fetch-global-layer-data db root)]
       {:status :success
        :data {:definitions definitions
               :selected-node selected-node
               :resolution resolution
               :values-by-node values-by-node
               :nodes nodes
               :global-layer global-layer}}))
   :cljs
   (defn get-inheritance-editor-data [& _]
     (throw (ex-info "Inheritance diagnostics are only available on the JVM server" {}))))

#?(:clj
   (defn get-inheritance-selector-matrix-data
     [{:keys [tenants]}]
     (let [conn (config-db/get-conn)]
       (if (nil? conn)
         {:status :error :error "No database connection"}
         (let [db @conn
               selected-tenants (->> (or tenants [])
                                     (map str)
                                     (map str/trim)
                                     (remove str/blank?)
                                     distinct
                                     sort
                                     vec)
               rows (mapv (fn [tenant]
                            {:tenant tenant
                             :cells (into {}
                                          (map (fn [root]
                                                 [root {:root root
                                                        :nodes (config-db/list-config-nodes db tenant root)}]))
                                          structure/config-roots-ordered)})
                          selected-tenants)]
           {:status :success
            :data {:roots structure/config-roots-ordered
                   :rows rows}}))))
   :cljs
   (defn get-inheritance-selector-matrix-data [& _]
     (throw (ex-info "Inheritance diagnostics are only available on the JVM server" {}))))

#?(:clj
   (defn get-global-defaults-data
     "Return data for the Global Defaults editor tab.
      {:definitions [inherit-owned defs sorted]
       :values-by-path {path value-entity} - current global values
       :version long - current global version
       :versions [recent entries with changelog]}"
     [& _opts]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             all-defs (config-db/get-all-definitions db)
             inherit-defs (->> all-defs
                               (filter #(= :inherit (:config-def/ownership %)))
                               (sort-by :config-def/path)
                               vec)
             paths-by-root (reduce (fn [acc d]
                                     (update acc (:config-def/root d)
                                             (fnil conj []) (:config-def/path d)))
                                   {}
                                   inherit-defs)
             values-by-path (reduce-kv
                             (fn [acc root paths]
                               (let [layer (fetch-global-layer-data db root)
                                     root-node-id (some-> layer :root-node :config.node/id)
                                     values (:values-by-node layer)]
                                 (if root-node-id
                                   (reduce (fn [a p]
                                             (if-let [v (get-in values [root-node-id p])]
                                               (assoc a p v)
                                               a))
                                           acc
                                           paths)
                                   acc)))
                             {}
                             paths-by-root)
             version (or (d/q '[:find (max ?v) .
                                :where [_ :config.global/version ?v]]
                              db)
                         0)
             versions (->> (d/q '[:find [(pull ?e [*]) ...]
                                  :where [?e :config.global/version]]
                                db)
                           (sort-by :config.global/version >)
                           (take 10)
                           vec)
             ;; Find the default global node per root, so the UI can target edits.
             global-nodes-by-root (reduce-kv
                                   (fn [acc root _]
                                     (if-let [node (:root-node (fetch-global-layer-data db root))]
                                       (assoc acc root node)
                                       acc))
                                   {}
                                   paths-by-root)]
         {:status :success
          :data {:definitions inherit-defs
                 :values-by-path values-by-path
                 :version version
                 :versions versions
                 :global-nodes-by-root global-nodes-by-root}})))
   :cljs
   (defn get-global-defaults-data [& _]
     (throw (ex-info "Global defaults data is only available on the JVM server" {}))))

#?(:clj
   (defn get-promotion-inspection-data
     "Run the ownership/inspect-definition-values op for the given path and
      bundle the result with the operator-facing clustering data used by the
      promotion wizard."
     [{:keys [path]}]
     (when-let [conn (config-db/get-conn)]
       (let [master-key (cfg/get-master-key)]
         {:status :success
          :data (ops-ownership/inspect-definition-values
                 @conn {:path path :master-key master-key})})))
   :cljs
   (defn get-promotion-inspection-data [& _]
     (throw (ex-info "Promotion inspection is only available on the JVM server" {}))))

#?(:clj
   (defn get-promotion-candidates
     "Return {:candidates [...] :version current-version} — the list of fork-owned
      definitions that the consensus heuristic suggests for promotion, along
      with the current global version so the UI can annotate \"Global v{n}\"."
     [& _]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             master-key (cfg/get-master-key)]
         {:status :success
          :data {:candidates (ops-ownership/suggested-for-promotion
                              db {:master-key master-key})
                 :version (or (d/q '[:find (max ?v) .
                                      :where [_ :config.global/version ?v]]
                                    db)
                              0)}})))
   :cljs
   (defn get-promotion-candidates [& _]
     (throw (ex-info "Promotion candidates are only available on the JVM server" {}))))

#?(:clj
   (defn get-inheritance-editor-comparison-data
     [{:keys [root tenants tenant-config-key selections]}]
     (let [normalized-selections (if (seq selections)
                                   (->> selections
                                        (keep (fn [{:keys [tenant root tenant-config-key node-id column-label selection-id]}]
                                                (let [tenant' (some-> tenant str str/trim)
                                                      tenant-config-key' (some-> tenant-config-key str str/trim)
                                                      node-id' (some-> node-id str str/trim)]
                                                  (when (and (seq tenant')
                                                             root
                                                             (or (seq tenant-config-key')
                                                                 (seq node-id')))
                                                    (cond-> {:tenant tenant'
                                                             :root root}
                                                      (seq tenant-config-key') (assoc :tenant-config-key tenant-config-key')
                                                      (seq node-id') (assoc :node-id node-id')
                                                      (some? column-label) (assoc :column-label column-label)
                                                      (some? selection-id) (assoc :selection-id selection-id))))))
                                        distinct
                                        vec)
                                   (->> (or tenants [])
                                        (map str)
                                        (map str/trim)
                                        (remove str/blank?)
                                        distinct
                                        (mapv (fn [tenant]
                                                {:tenant tenant
                                                 :root root
                                                 :tenant-config-key tenant-config-key}))))
           results (mapv (fn [{:keys [tenant root tenant-config-key node-id column-label selection-id] :as selection}]
                           (let [{:keys [status data error]} (get-inheritance-editor-data selection)]
                             (cond-> {:tenant tenant
                                      :root root
                                      :tenant-config-key tenant-config-key
                                      :node-id node-id
                                      :column-label column-label
                                      :selection-id selection-id
                                      :status status}
                               data (assoc :data data)
                               error (assoc :error error))))
                         normalized-selections)
           conn (config-db/get-conn)
           db @conn
           global-version (or (d/q '[:find (max ?v) .
                                     :where [_ :config.global/version ?v]]
                                   db)
                              0)]
       {:status :success
        :data {:selections normalized-selections
               :results results
               :global-version global-version}}))
   :cljs
   (defn get-inheritance-editor-comparison-data [& _]
     (throw (ex-info "Inheritance diagnostics are only available on the JVM server" {}))))

#?(:clj
   (defn export-preview
     "Get export preview for UI."
     [tenant include-audit? user-id]
     (with-admin-conn user-id
       (fn [{:keys [conn]}]
         (let [opts {:dry-run? true :include-audit? include-audit?}]
           (if tenant
             (ops-sync/export-tenant conn tenant opts)
             (ops-sync/export-full conn opts)))))))

#?(:clj
   (defn do-export!
     "Execute export and return JSON string for download."
     [tenant include-audit? export-password user-id]
     (with-admin-conn user-id
       (fn [{:keys [conn master-key]}]
         (let [opts {:master-key master-key
                     :export-password export-password
                     :include-audit? include-audit?}
               data (if tenant
                      (ops-sync/export-tenant conn tenant opts)
                      (ops-sync/export-full conn opts))]
           (json/write-str data :escape-slash false))))))

#?(:clj
   (defn import-preview
     "Get import preview from JSON data string."
     [json-str on-conflict user-id]
     (with-admin-conn user-id
       (fn [{:keys [conn]}]
         (let [data (json/read-str json-str :key-fn keyword)]
           (ops-sync/import-data conn data {:on-conflict on-conflict :dry-run? true}))))))

#?(:clj
   (defn do-import!
     "Execute import from JSON data string."
     [json-str import-password on-conflict user-id & [progress-atom]]
     (with-admin-conn user-id
       (fn [{:keys [conn master-key]}]
         (let [data (json/read-str json-str :key-fn keyword)]
           (ops-sync/import-data conn data {:master-key master-key
                                       :export-password import-password
                                       :on-conflict on-conflict
                                       :progress-atom progress-atom}))))))

#?(:clj
   (defn preview-clone-tenant
     "Preview cloning a source tenant into a new target tenant."
     [source-tenant target-tenant exclude-dataset-pipelines? user-id]
     (with-admin-conn user-id
       (fn [{:keys [conn]}]
         (ops-clone/preview-clone-tenant! conn
                                          source-tenant
                                          target-tenant
                                          {:exclude-dataset-pipelines? exclude-dataset-pipelines?})))))

#?(:clj
   (defn do-clone-tenant!
     "Clone a source tenant into a new target tenant."
     [source-tenant target-tenant exclude-dataset-pipelines? user-id]
     (with-admin-conn user-id
       (fn [{:keys [conn]}]
         (ops-clone/clone-tenant! conn
                                  source-tenant
                                  target-tenant
                                  {:exclude-dataset-pipelines? exclude-dataset-pipelines?})))))

#?(:clj
   (defn preview-tenant-retirement
     "Preview source-tenant retirement for the current deployment cutover set."
     [tenant-input tenant-config-key user-id]
     (with-admin-conn user-id
       (fn [{:keys [conn]}]
         (ops-retirement/preview-tenant-retirement! conn
                                                    {:tenants (parse-tenant-list-input tenant-input)
                                                     :tenant-config-key (or (not-empty (str/trim (or tenant-config-key "")))
                                                                            "default")})))))

#?(:clj
   (defn do-retire-source-tenants!
     "Retire source tenants after the target topology has been verified."
     [tenant-input tenant-config-key conversation-strategy user-id]
     (with-admin-conn user-id
       (fn [{:keys [conn]}]
         (ops-retirement/retire-source-tenants! conn
                                                {:tenants (parse-tenant-list-input tenant-input)
                                                 :tenant-config-key (or (not-empty (str/trim (or tenant-config-key "")))
                                                                        "default")
                                                 :dry-run? false
                                                 :conversation-strategy conversation-strategy})))))

#?(:clj
   (defn do-bootstrap-deployment-target-topology!
     "Bootstrap the refactor-aware deployment target topology."
     [tenant-config-key user-id]
     (with-admin-conn user-id
       (fn [{:keys [conn master-key]}]
         (ops-topology/bootstrap-deployment-target-topology!
          conn
          {:master-key master-key
           :tenant-config-keys [(or (not-empty (str/trim (or tenant-config-key "")))
                                    "default")]})))))
