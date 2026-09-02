(ns digdir.config.ui
  "Configuration management UI for the V2 config model.

   Features:
   - Diagnostics-first tree inspection across platform, runtime, and dataset roots
   - Tree editing, previews, and rollback snapshots
   - Audit log viewer
   - Permissions and API key management"
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            [digdir.config.ui.audit :refer [AuditLog]]
            [digdir.config.ui.permissions :refer [Permissions]]
            [digdir.config.ui.api-keys :refer [APIKeys]]
            [digdir.config.ui.styles :as styles]
            [digdir.config.ui.common :as common]
            [digdir.config.ui.inheritance :refer [ConfigInheritanceEditor]]
            [digdir.config.ui.global :refer [GlobalDefaultsEditor]]
            #?(:clj [digdir.pipeline.core :as pipeline])
            #?(:clj [digdir.ui.deployment :as deployment])
            [digdir.skills.ui :refer [SkillsUI]]
            [digdir.i18n :refer [t]]
            [digdir.ui.routing :as routing]
            #?(:clj [digdir.config.core :as cfg])
            #?(:clj [digdir.config.accessor :as accessor])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.config.crypto :as crypto])
            #?(:clj [digdir.config.permissions :as perms])
            #?(:clj [clojure.data.json :as json])
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.edn :as edn])
            #?(:clj [digdir.data.db :as db])))

;; =============================================================================
;; Styles
;; =============================================================================

(def input-style styles/input-style)
(def select-style styles/select-style)
(def badge-style styles/badge-style)
(def sensitivity-colors styles/sensitivity-colors)

(def resolution-colors
  "Colors for resolution level badges using multi-dimensional model.
   Organized from least specific (global) to most specific (pipeline+tenant+env)."
  styles/resolution-colors)

(def modal-backdrop-style styles/modal-backdrop-style)
(def modal-content-style styles/modal-content-style)
(def textarea-style styles/textarea-style)

(defn button-style
  "Generate button style.
   variant: :primary, :secondary, :danger, :success
   size: :normal (default), :small"
  ([variant] (styles/button-style variant))
  ([variant size] (styles/button-style variant size)))


;; =============================================================================
;; Helper Functions
;; =============================================================================

#?(:clj
   (defn format-timestamp
     "Format an epoch-ms timestamp for display in the Oslo time zone."
     [epoch-ms]
     (common/format-timestamp epoch-ms)))

(defn mask-secret
  "Mask a secret value for display."
  [value]
  (common/mask-secret value))

(defn truncate-value
  "Truncate a value for display in compact cells."
  [value max-len]
  (common/truncate-value value max-len))

(defn parse-trace-path-input
  "Parse a textarea-style path list into a stable vector of non-blank config paths."
  [path-input]
  (common/parse-trace-path-input path-input))

(defn binding-types-for-root
  "Bindings are deprecated. The diagnostics UI no longer offers binding creation."
  [root]
  (common/binding-types-for-root root))

(defn parent-preview-label
  "Describe a pending parent change for display in the tree inspector."
  [current-parent-id target-parent-id]
  (common/parent-preview-label current-parent-id target-parent-id))

(defn preview-display-value
  "Render a compact preview value for semantic dry-run output."
  [value]
  (common/preview-display-value value))

(defn node-delete-preview-label
  "Describe what a node deletion will remove."
  [node]
  (common/node-delete-preview-label node))

(defn node-delete-preview-lines
  "Describe the concrete bindings and direct values removed by node deletion."
  [node]
  (common/node-delete-preview-lines node))

(defn binding-delete-preview-label
  "Describe the concrete binding removed by a delete-binding mutation."
  [opts]
  (common/binding-delete-preview-label opts))

(defn value-reset-preview-label
  "Describe the concrete direct value removed by a reset mutation."
  [opts]
  (common/value-reset-preview-label opts))

(defn value-reset-impact-summary-label
  "Summarize the effective value after a direct node value reset."
  [opts]
  (common/value-reset-impact-summary-label opts))

(defn node-dirty-fields
  "Return the mutable node fields that differ from the persisted node."
  [node draft-label draft-tenant-config-key draft-enabled draft-parent-id]
  (common/node-dirty-fields node draft-label draft-tenant-config-key draft-enabled draft-parent-id))

(defn dirty-fields-label
  "Render a compact unsaved-changes label from changed field keywords."
  [fields]
  (common/dirty-fields-label fields))

(def ^:private soft-depth-warning-threshold
  4)

(defn depth-warning-label
  "Render a soft warning when a node sits deeper than the recommended depth."
  [depth]
  (common/depth-warning-label depth))

(defn effective-disabled-label
  "Describe why a node is effectively disabled."
  [opts]
  (common/effective-disabled-label opts))

(defn reparent-impact-summary-label
  "Summarize a semantic reparent preview for display on a node card."
  [opts]
  (common/reparent-impact-summary-label opts))

(defn selector-binding-type?
  "Return true when a binding type selects a node directly."
  [binding-type]
  (common/selector-binding-type? binding-type))

(defn binding-preview-summary-label
  "Summarize the semantic effect of creating legacy binding metadata."
  [opts]
  (common/binding-preview-summary-label opts))

(defn binding-preview-note-label
  "Describe how a pending binding participates as metadata."
  [opts]
  (common/binding-preview-note-label opts))

(defn value-editor-status-label
  "Return an inline status label for the node value editor."
  [value-path selected-definition]
  (common/value-editor-status-label value-path selected-definition))

#?(:clj
   (defn mutation-success-label
     "Build a stable user-facing success message for a tree mutation."
     [opts]
     (common/mutation-success-label opts)))

#?(:clj
   (defn parse-value-by-type
     "Safely parse a string value based on its type.
      Returns the parsed value or throws an exception for invalid input."
     [value value-type]
     (common/parse-value-by-type value value-type)))

#?(:clj
   (defn trace-stop-reason-label
     "Convert an internal trace stop reason into UI-facing text."
     [stop-reason]
     (common/trace-stop-reason-label stop-reason)))

#?(:clj
   (defn format-runtime-trace
     "Normalize a runtime trace map into a UI-friendly diagnostic summary."
     [path trace]
     (common/format-runtime-trace path trace)))

(defn sanitize-tenant-id
  "Normalize tenant IDs to allowed characters, preferring lowercase.
   Allowed characters: a-z, 0-9, and hyphen."
  [s]
  (common/sanitize-tenant-id s))

(defn sanitize-config-node-fragment
  "Normalize a human-facing node label/tenant-config-key into a stable node-id fragment."
  [s]
  (common/sanitize-config-node-fragment s))

(defn suggest-config-node-id
  "Build a stable node-id suggestion from root, tenant, label/tenant-config-key, and existing nodes."
  [root tenant label tenant-config-key existing-node-ids]
  (common/suggest-config-node-id root tenant label tenant-config-key existing-node-ids))

(defn config-node-option-label
  "Render a UI label for a config node option without forcing the user to know the raw ID."
  [node]
  (common/config-node-option-label node))

(defn valid-tenant-id?
  "Validate tenant ID using allowed characters and shape."
  [tenant-id]
  (common/valid-tenant-id? tenant-id))

(defn group-paths-by-segment
  "Group config definitions by their first path segment."
  [definitions]
  (common/group-paths-by-segment definitions))

(defn node-matches-filter?
  "Return true when a diagnostics node matches a free-text focus filter."
  [node node-filter]
  (let [needle (some-> node-filter str/trim str/lower-case)]
    (or (str/blank? needle)
        (->> [(:node-id node) (:label node) (:tenant-config-key node)]
             (keep identity)
             (map str/lower-case)
             (some #(str/includes? % needle))))))

;; =============================================================================
;; Server-side Data Functions
;; =============================================================================

#?(:clj
   (defn get-inheritance-editor-data
     "Load inheritance diagnostics for the configuration editor.
      Returns {:status :success :data ...} or {:status :error :error message}."
     [opts]
     (common/get-inheritance-editor-data opts))
   :cljs
   (defn get-inheritance-editor-data [& _]
     (throw (ex-info "Inheritance diagnostics are only available on the JVM server" {}))))

#?(:clj
   (defn get-runtime-trace-data
     "Load runtime V2 config traces in a UI-friendly shape.
      Returns {:status :success :data ...} or {:status :error :error message}."
     [opts]
     (common/get-runtime-trace-data opts))
   :cljs
   (defn get-runtime-trace-data [& _]
     (throw (ex-info "Runtime trace diagnostics are only available on the JVM server" {}))))

#?(:clj
   (defn build-config-tree-diagnostics
     "Combine nodes, bindings, and optional dataset catalog into a UI-friendly tree summary."
     [root tenant nodes bindings opts]
     (common/build-config-tree-diagnostics root tenant nodes bindings opts))
   :cljs
   (defn build-config-tree-diagnostics [& _]
     (throw (ex-info "Config tree diagnostics are only available on the JVM server" {}))))

#?(:clj
   (defn get-config-tree-diagnostics-data
     "Load root-local node and binding diagnostics in a UI-friendly shape.
      Returns {:status :success :data ...} or {:status :error :error message}."
     [opts]
     (try
       (let [conn (config-db/get-conn)]
         (if (nil? conn)
           {:status :error :error "No database connection"}
           (let [db @conn
                 root (or (:root opts) :runtime)
                 tenant (:tenant opts)
                 nodes (config-db/list-config-nodes db tenant root)
                 definitions (config-db/get-definitions-by-root db root)
                 node-values-by-node (into {}
                                           (map (fn [node]
                                                  [(:config.node/id node)
                                                   (config-db/list-node-values db
                                                                               root
                                                                               tenant
                                                                               (:config.node/id node))]))
                                           nodes)
                 datasets (when (= :dataset root) (config-db/list-datasets db))
                 pipelines (when (= :dataset root) (config-db/list-dataset-pipelines db))
                 master-key (cfg/get-master-key)]
             {:status :success
                 :data (common/build-config-tree-diagnostics root tenant nodes []
                                                      {:db db
                                                       :datasets datasets
                                                       :pipelines pipelines
                                                       :definitions definitions
                                                       :node-values-by-node node-values-by-node
                                                       :master-key master-key})})))
       (catch Exception e
         {:status :error :error (or (ex-message e) "Tree diagnostics failed")})))
   :cljs
   (defn get-config-tree-diagnostics-data [& _]
     (throw (ex-info "Config tree diagnostics are only available on the JVM server" {}))))

#?(:clj
   (defn get-node-parent-preview-data
     "Preview the semantic impact of changing a node's parent without
      committing the mutation."
     [opts]
     (try
       (let [conn (config-db/get-conn)]
         (if (nil? conn)
           {:status :error :error "No database connection"}
           (let [db @conn
                 tenant (:tenant opts)
                 root (:root opts)
                 node-id (:node-id opts)
                 parent-id (:parent-id opts)
                 definitions (config-db/get-definitions-by-root db root)
                 defs-by-path (into {} (map (juxt :config-def/path identity)) definitions)
                 paths (mapv :config-def/path definitions)
                 master-key (cfg/get-master-key)
                 {:keys [current-parent-id preview-parent-id before after]}
                 (config-db/preview-node-parent-change db root tenant node-id parent-id paths)
                 diffs (->> paths
                            (keep (fn [path]
                                    (let [definition (get defs-by-path path)
                                          before-entry (get-in before [:results path])
                                          after-entry (get-in after [:results path])
                                          before-trace (:trace before-entry)
                                          after-trace (:trace after-entry)
                                          before-value (when-let [value (:value before-entry)]
                                                         (config-db/decode-value (:config.value/raw value)
                                                                                 (:config-def/value-type definition)
                                                                                 (:config-def/encrypted? definition)
                                                                                 master-key))
                                          after-value (when-let [value (:value after-entry)]
                                                        (config-db/decode-value (:config.value/raw value)
                                                                                (:config-def/value-type definition)
                                                                                (:config-def/encrypted? definition)
                                                                                master-key))
                                          before-winning-node (:winning-node before-trace)
                                          after-winning-node (:winning-node after-trace)
                                          changed-value? (not= before-value after-value)
                                          changed-winning-node? (not= before-winning-node after-winning-node)
                                          changed-stop-reason? (not= (:stop-reason before-trace)
                                                                     (:stop-reason after-trace))]
                                      (when (or changed-value? changed-winning-node? changed-stop-reason?)
                                        {:path path
                                         :before-value (preview-display-value before-value)
                                         :after-value (preview-display-value after-value)
                                         :before-winning-node before-winning-node
                                         :after-winning-node after-winning-node
                                         :before-stop-reason (:stop-reason before-trace)
                                         :after-stop-reason (:stop-reason after-trace)
                                         :changed-value? changed-value?
                                         :changed-winning-node? changed-winning-node?}))))
                            vec)
                 changed-value-count (count (filter :changed-value? diffs))
                 changed-winning-node-count (count (filter :changed-winning-node? diffs))]
             {:status :success
              :data {:node-id node-id
                     :current-parent-id current-parent-id
                     :preview-parent-id preview-parent-id
                     :changed-path-count (count diffs)
                     :changed-value-count changed-value-count
                     :changed-winning-node-count changed-winning-node-count
                     :paths diffs}})))
       (catch Exception e
         {:status :error :error (or (ex-message e) "Node parent preview failed")})))
   :cljs
   (defn get-node-parent-preview-data [& _]
     (throw (ex-info "Node parent preview is only available on the JVM server" {}))))

#?(:clj
   (defn get-binding-preview-data
     "Bindings are deprecated and no longer supported by the diagnostics UI."
     [opts]
     (common/get-binding-preview-data opts))
   :cljs
   (defn get-binding-preview-data [& _]
     (throw (ex-info "Binding preview is only available on the JVM server" {}))))

#?(:clj
   (defn get-node-value-reset-preview-data
     "Preview the effective value after removing a direct node value."
     [opts]
     (try
       (let [conn (config-db/get-conn)]
         (if (nil? conn)
           {:status :error :error "No database connection"}
           (let [db @conn
                 tenant (:tenant opts)
                 root (:root opts)
                 node-id (:node-id opts)
                 path (:path opts)
                 definition (or (config-db/get-definition db path)
                                (throw (ex-info "Config definition not found" {:path path})))
                 _ (when-not (= root (:config-def/root definition))
                     (throw (ex-info "Config definition root mismatch"
                                     {:path path
                                      :requested-root root
                                      :definition-root (:config-def/root definition)})))
                 master-key (cfg/get-master-key)
                 {:keys [before after]} (config-db/preview-node-value-reset-change db root tenant node-id path)
                 before-entry (get-in before [:results path])
                 after-entry (get-in after [:results path])
                 before-value (when-let [value (:value before-entry)]
                                (config-db/decode-value (:config.value/raw value)
                                                        (:config-def/value-type definition)
                                                        (:config-def/encrypted? definition)
                                                        master-key))
                 after-value (when-let [value (:value after-entry)]
                               (config-db/decode-value (:config.value/raw value)
                                                       (:config-def/value-type definition)
                                                       (:config-def/encrypted? definition)
                                                       master-key))
                 before-trace (:trace before-entry)
                 after-trace (:trace after-entry)]
             {:status :success
              :data {:path path
                     :before-value (preview-display-value before-value)
                     :after-value (preview-display-value after-value)
                     :before-winning-node (:winning-node before-trace)
                     :after-winning-node (:winning-node after-trace)
                     :before-stop-reason (:stop-reason before-trace)
                     :after-stop-reason (:stop-reason after-trace)}})))
       (catch Exception e
         {:status :error :error (or (ex-message e) "Node value reset preview failed")})))
   :cljs
   (defn get-node-value-reset-preview-data [& _]
     (throw (ex-info "Node value reset preview is only available on the JVM server" {}))))

#?(:clj
   (defn mutate-config-tree!
     "Perform a root-local config tree mutation for the admin diagnostics UI."
     [opts]
     (common/mutate-config-tree! opts))
   :cljs
   (defn mutate-config-tree! [& _]
     (throw (ex-info "Config tree mutations are only available on the JVM server" {}))))

#?(:clj
   (defn create-config-tree-binding!
     "Create a config binding through the Operator Console diagnostics surface."
     [tenant root node-id binding-type binding-value user-id]
     (common/create-config-tree-binding! tenant root node-id binding-type binding-value user-id))
   :cljs
   (defn create-config-tree-binding! [& _]
     (throw (ex-info "Config tree binding creation is only available on the JVM server" {}))))

#?(:clj
   (defn create-dataset-handler!
     "Create a new durable parent dataset for operator workflows."
     [name description user-id]
     (common/create-dataset-handler! name description user-id))
   :cljs
   (defn create-dataset-handler! [& _]
     (throw (ex-info "Dataset creation is only available on the JVM server" {}))))

#?(:clj
   (defn create-pipeline-handler!
     "Create a new pipeline."
     [tenant dataset-id pipeline-id properties user-id]
     (common/create-pipeline-handler! tenant dataset-id pipeline-id properties user-id)))

#?(:clj
   (defn create-tenant-handler!
     "Create a new tenant with ID and display name."
     [tenant-id tenant-name user-id]
     (common/create-tenant-handler! tenant-id tenant-name user-id)))

#?(:clj
   (defn duplicate-pipeline-handler!
     "Duplicate an existing pipeline."
     [tenant source-pipeline-id new-pipeline-id user-id]
     (common/duplicate-pipeline-handler! tenant source-pipeline-id new-pipeline-id user-id)))

#?(:clj
   (defn soft-delete-pipeline-handler!
     "Soft-delete an pipeline."
     [tenant pipeline-id user-id]
     (common/soft-delete-pipeline-handler! tenant pipeline-id user-id)))

;; =============================================================================
;; Operations Handlers (Server-side)
;; =============================================================================

#?(:clj
   (defn export-preview
     "Get export preview for UI."
     [tenant include-audit? user-id]
     (common/export-preview tenant include-audit? user-id)))

#?(:clj
   (defn do-export!
     "Execute export and return JSON string for download."
     [tenant include-audit? export-password user-id]
     (common/do-export! tenant include-audit? export-password user-id)))

#?(:clj
   (defn import-preview
     "Get import preview from JSON data string."
     [json-str on-conflict user-id]
     (common/import-preview json-str on-conflict user-id)))

#?(:clj
   (defn do-import!
     "Execute import from JSON data string."
     [json-str import-password on-conflict user-id & [progress-atom]]
     (common/do-import! json-str import-password on-conflict user-id progress-atom)))

#?(:clj
   (defn preview-clone-tenant
     "Preview cloning a source tenant into a new target tenant."
     [source-tenant target-tenant exclude-dataset-pipelines? user-id]
     (common/preview-clone-tenant source-tenant target-tenant exclude-dataset-pipelines? user-id)))

#?(:clj
   (defn do-clone-tenant!
     "Clone a source tenant into a new target tenant."
     [source-tenant target-tenant exclude-dataset-pipelines? user-id]
     (common/do-clone-tenant! source-tenant target-tenant exclude-dataset-pipelines? user-id)))

#?(:clj
   (defn preview-tenant-retirement
     "Preview source-tenant retirement."
     [tenant-input tenant-config-key user-id]
     (common/preview-tenant-retirement tenant-input tenant-config-key user-id)))

#?(:clj
   (defn do-retire-source-tenants!
     "Apply source-tenant retirement."
     [tenant-input tenant-config-key conversation-strategy user-id]
     (common/do-retire-source-tenants! tenant-input tenant-config-key conversation-strategy user-id)))

#?(:clj
   (defn do-bootstrap-deployment-target-topology!
     "Bootstrap the target deployment topology."
     [tenant-config-key user-id]
     (common/do-bootstrap-deployment-target-topology! tenant-config-key user-id)))

;; =============================================================================
;; UI Helpers
;; =============================================================================

(e/defn Modal
  "Reusable modal component with backdrop, click-outside-to-close, and event isolation.

   Parameters:
   - !open: atom controlling modal visibility (will be reset on close)
   - opts: optional map with:
     - :style - merged into dialog styles
     - :z-index - override backdrop z-index (default \"1000\")
     - :on-close - optional callback called when modal closes (click outside or programmatic)
   - Content: Electric component (e/fn) to render inside the modal"
  ([!open opts Content]
   (e/client
    (let [open-val (e/watch !open)]
      (when open-val
        (let [on-close     (:on-close opts)
              close!       (fn []
                             (when on-close (on-close))
                             (reset! !open (if (boolean? open-val) false nil)))
              z-index      (or (:z-index opts) "1000")
              dialog-style (merge (merge modal-content-style
                                         {:position "relative"})
                                  (:style opts))]
          (dom/div
           (dom/props {:style (merge modal-backdrop-style
                                     {:z-index z-index})})
           ;; Close on backdrop click, and absorb all mouse events
           (dom/On "click" (fn [_] (close!)) nil)
           (dom/On "mousedown" (fn [e] (.stopPropagation e)) nil)
           (dom/On "mouseup" (fn [e] (.stopPropagation e)) nil)
           (dom/On "wheel" (fn [e] (.stopPropagation e)) nil)

           ;; Dialog content
           (dom/div
            (dom/props {:style dialog-style})
            ;; Stop click from closing modal when clicking inside
            (dom/On "click" (fn [e] (.stopPropagation e)) nil)
            ;; Stop wheel events from bubbling
            (dom/On "wheel" (fn [e] (.stopPropagation e)) nil)

            (Content)))))))))

(e/defn ModalButton [label variant On-click opts]
  (let [disabled (:disabled opts)]
    (dom/button
     (dom/props {:style    (merge (button-style variant)
                                  (when disabled {:opacity "0.5"}))
                 :disabled disabled})
     (dom/text label)
     (when-not disabled
       (let [[tok _] (e/Token (dom/On "click" identity nil))]
         (when tok
           (when On-click
             (On-click))
           (tok)))))))

;; =============================================================================
;; UI Components
;; =============================================================================

;; -----------------------------------------------------------------------------
;; Modal Components
;; -----------------------------------------------------------------------------

(e/defn MultilineEditModal [path value-type initial-value On-save On-reset has-value !show-modal]
  "Modal for editing values with Reset button to remove the value."
  (e/client
   (let [vtype       (or value-type :string) ;; Ensure value-type is never nil
         default-value (if (= vtype :boolean)
                         (let [normalized (some-> initial-value str str/trim str/lower-case)]
                           (cond
                             (#{"true" "1" "yes"} normalized) "true"
                             (#{"false" "0" "no"} normalized) "false"
                             :else "__unset"))
                         (or initial-value ""))
         !edit-value (atom default-value)
         edit-value  (e/watch !edit-value)
         show-modal  (e/watch !show-modal)]

     (when show-modal
       (Modal
        !show-modal
        nil
        (e/fn []
          (dom/div
           (dom/props {:style {:display         "flex"
                               :justify-content "space-between"
                               :align-items     "center"
                               :margin-bottom   "1rem"}})
           (dom/div
            (dom/props {:style {:font-weight "600"
                                :font-size   "1rem"}})
            (dom/text (t :config/edit-title path)))
           (dom/button
            (dom/props {:style {:background "none"
                                :border     "none"
                                :font-size  "1.5rem"
                                :cursor     "pointer"
                                :color      "#6b7280"}})
            (dom/text "×")
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (reset! !show-modal false)
                (tok)))))

          ;; Type indicator
          (dom/div
           (dom/props {:style {:font-size     "0.75rem"
                               :color         "#6b7280"
                               :margin-bottom "0.5rem"}})
           (dom/text (str "Type: " (name vtype))))
          ;; Input based on type
          (case vtype
            :edn
            (dom/textarea
             (dom/props {:style textarea-style
                         :value edit-value
                         :rows  15})
             (dom/On "input" #(reset! !edit-value (.. % -target -value)) nil))

            :boolean
            (dom/div
             (dom/props {:style {:display "flex"
                                 :flex-direction "column"
                                 :gap "0.5rem"}})
             (let [group-name (str "bool-" path)]
               (dom/label
                (dom/props {:style {:display "flex"
                                    :align-items "center"
                                    :gap "0.5rem"
                                    :cursor "pointer"}})
                (dom/input
                 (dom/props {:type "radio"
                             :name group-name
                             :value "__unset"
                             :checked (= edit-value "__unset")})
                 (dom/On "change" #(reset! !edit-value (.. % -target -value)) nil))
                (dom/text "no value"))
               (dom/label
                (dom/props {:style {:display "flex"
                                    :align-items "center"
                                    :gap "0.5rem"
                                    :cursor "pointer"}})
                (dom/input
                 (dom/props {:type "radio"
                             :name group-name
                             :value "true"
                             :checked (= edit-value "true")})
                 (dom/On "change" #(reset! !edit-value (.. % -target -value)) nil))
                (dom/text "true"))
               (dom/label
                (dom/props {:style {:display "flex"
                                    :align-items "center"
                                    :gap "0.5rem"
                                    :cursor "pointer"}})
                (dom/input
                 (dom/props {:type "radio"
                             :name group-name
                             :value "false"
                             :checked (= edit-value "false")})
                 (dom/On "change" #(reset! !edit-value (.. % -target -value)) nil))
                (dom/text "false"))))

            :number
            (dom/input
             (dom/props {:type  "number"
                         :value edit-value
                         :style input-style})
             (dom/On "input" #(reset! !edit-value (.. % -target -value)) nil))

            ;; Default (including :string): textarea for flexibility
            (dom/textarea
             (dom/props {:style textarea-style
                         :value edit-value
                         :rows  5})
             (dom/On "input" #(reset! !edit-value (.. % -target -value)) nil)))

          (dom/div
           (dom/props {:style {:display         "flex"
                               :gap             "0.5rem"
                               :justify-content "flex-end"
                               :margin-top      "1rem"}})
           (ModalButton (t :config/reset) :danger
                        (e/fn [] (On-reset)) {:disabled (not has-value)})
           (ModalButton (t :config/cancel) :secondary
                        (e/fn [] (reset! !show-modal false)) nil)
           (ModalButton (t :config/save) :primary
                        (e/fn []
                          (if (and (= vtype :boolean) (= edit-value "__unset"))
                            (On-reset)
                            (On-save edit-value)))
                        nil))))))))

(e/defn SecretEditModal [path value-length On-save On-reset has-value !show-modal]
  "Modal for editing secret/encrypted values.
   Shows asterisks matching the current value length as placeholder.
   Input is shown as plain text (not masked) as user types."
  (e/client
   (let [!edit-value (atom "")
         edit-value  (e/watch !edit-value)
         show-modal  (e/watch !show-modal)
         ;; Create placeholder with asterisks matching value length
         placeholder (if (and has-value (pos? (or value-length 0)))
                       (apply str (repeat value-length "*"))
                       "Enter new value")]

     (when show-modal
       (Modal
        !show-modal
        nil
        (e/fn []
          (dom/div
           (dom/props {:style {:display         "flex"
                               :justify-content "space-between"
                               :align-items     "center"
                               :margin-bottom   "1rem"}})
           (dom/div
            (dom/props {:style {:font-weight "600"
                                :font-size   "1rem"}})
            (dom/text (t :config/edit-title path)))
           (dom/button
            (dom/props {:style {:background "none"
                                :border     "none"
                                :font-size  "1.5rem"
                                :cursor     "pointer"
                                :color      "#6b7280"}})
            (dom/text "×")
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (reset! !show-modal false)
                (tok)))))

          ;; Type indicator
          (dom/div
           (dom/props {:style {:font-size     "0.75rem"
                               :color         "#6b7280"
                               :margin-bottom "0.5rem"}})
           (dom/text "Type: secret (encrypted)"))

          ;; Help text
          (dom/div
           (dom/props {:style {:font-size     "0.75rem"
                               :color         "#9ca3af"
                               :margin-bottom "0.75rem"
                               :font-style    "italic"}})
           (dom/text (if has-value
                       "Enter a new value to replace the existing secret."
                       "Enter the secret value.")))

          ;; Secret input - plain text, not password type
          (dom/input
           (dom/props {:type        "text"
                       :value       edit-value
                       :placeholder placeholder
                       :style       (merge input-style
                                           {:font-family "monospace"
                                            :width       "100%"})})
           (dom/On "input" #(reset! !edit-value (.. % -target -value)) nil))

          (dom/div
           (dom/props {:style {:display         "flex"
                               :gap             "0.5rem"
                               :justify-content "flex-end"
                               :margin-top      "1rem"}})
           (ModalButton (t :config/reset) :danger
                        (e/fn [] (On-reset)) {:disabled (not has-value)})
           (ModalButton (t :config/cancel) :secondary
                        (e/fn [] (reset! !show-modal false)) nil)
           (ModalButton (t :config/save) :primary
                        (e/fn [] (when (seq edit-value) (On-save edit-value)))
                        {:disabled (empty? edit-value)}))))))))

(e/defn NewTenantModal [existing-tenant-ids user-id !refresh-counter !show-modal]
  "Modal for creating a new tenant with name and ID."
  (e/client
   (let [!tenant-name        (atom "")
         !tenant-id          (atom "")
         !id-manually-edited (atom false)
         tenant-name         (e/watch !tenant-name)
         tenant-id           (e/watch !tenant-id)
         id-manually-edited  (e/watch !id-manually-edited)
         suggested-id        (sanitize-tenant-id tenant-name)
         tenant-exists?      (contains? (set existing-tenant-ids) tenant-id)
         id-valid?           (valid-tenant-id? tenant-id)]
     (Modal
      !show-modal
      nil
      (e/fn []
        (dom/div
         (dom/props {:style {:display         "flex"
                             :justify-content "space-between"
                             :align-items     "center"
                             :margin-bottom   "1rem"}})
         (dom/div
          (dom/props {:style {:font-weight "600"
                              :font-size   "1rem"}})
          (dom/text "Create New Tenant"))
         (dom/button
          (dom/props {:style {:background "none"
                              :border     "none"
                              :font-size  "1.5rem"
                              :cursor     "pointer"
                              :color      "#6b7280"}})
          (dom/text "×")
          (let [[tok _] (e/Token (dom/On "click" identity nil))]
            (when tok
              (reset! !show-modal nil)
              (tok)))))
        ;; Tenant name field
        (dom/div
         (dom/props {:style {:margin-bottom "1rem"}})
         (dom/label
          (dom/props {:style {:display       "block"
                              :font-weight   "500"
                              :margin-bottom "0.25rem"}})
          (dom/text "Name *"))
         (dom/input
          (dom/props {:type        "text"
                      :placeholder "e.g., My Tenant"
                      :value       tenant-name
                      :style       input-style})
          (dom/On "input"
                  (fn [e]
                    (let [v (.. e -target -value)
                          next-id (sanitize-tenant-id v)]
                      (reset! !tenant-name v)
                      (when-not id-manually-edited
                        (reset! !tenant-id next-id))))
                  nil)))
        ;; Tenant ID field
        (dom/div
         (dom/props {:style {:margin-bottom "0.25rem"}})
         (dom/label
          (dom/props {:style {:display       "block"
                              :font-weight   "500"
                              :margin-bottom "0.25rem"}})
          (dom/text "ID *"))
         (dom/input
          (dom/props {:type        "text"
                      :placeholder "e.g., my-tenant"
                      :value       tenant-id
                      :style       (merge input-style {:font-family "monospace"})})
          (dom/On "input"
                  (fn [e]
                    (let [raw (.. e -target -value)]
                      (reset! !id-manually-edited true)
                      (reset! !tenant-id (sanitize-tenant-id raw))))
                  nil)))
        (dom/div
         (dom/props {:style {:font-size     "0.75rem"
                             :color         "#6b7280"
                             :margin-bottom "0.25rem"}})
         (dom/text "Allowed: lowercase letters, numbers, and hyphens (a-z, 0-9, -)."))
        (when (and (seq suggested-id) (not= suggested-id tenant-id))
          (dom/div
           (dom/props {:style {:font-size     "0.75rem"
                               :color         "#1e40af"
                               :margin-bottom "0.5rem"}})
           (dom/text (str "Suggested ID: " suggested-id))))
        (when (and (seq tenant-id) (not id-valid?))
          (dom/div
           (dom/props {:style {:font-size     "0.75rem"
                               :color         "#dc2626"
                               :margin-bottom "0.5rem"}})
           (dom/text "Invalid ID format.")))
        (when tenant-exists?
          (dom/div
           (dom/props {:style {:font-size     "0.75rem"
                               :color         "#dc2626"
                               :margin-bottom "0.5rem"}})
           (dom/text "Tenant ID already exists.")))
        (dom/div
         (dom/props {:style {:display         "flex"
                             :gap             "0.5rem"
                             :justify-content "flex-end"
                             :margin-top      "1rem"}})
         (ModalButton (t :config/cancel) :secondary
                      (e/fn [] (reset! !show-modal nil)) nil)
         (ModalButton (t :config/create) :primary
                      (e/fn []
                        (e/server (create-tenant-handler! tenant-id tenant-name user-id))
                        (reset! !show-modal nil)
                        (swap! !refresh-counter inc))
                      {:disabled (or (str/blank? tenant-name)
                                     (str/blank? tenant-id)
                                     tenant-exists?
                                     (not id-valid?))})))))))

(e/defn NewPipelineModal [tenant on-create !show-modal]
  "Modal for creating a new pipeline."
  (e/client
   (let [!pipeline-id   (atom "")
         !pipeline-name (atom "")
         pipeline-id    (e/watch !pipeline-id)
         pipeline-name  (e/watch !pipeline-name)]
     (Modal
      !show-modal
      nil
      (e/fn []
        (dom/div
         (dom/props {:style {:display         "flex"
                             :justify-content "space-between"
                             :align-items     "center"
                             :margin-bottom   "1rem"}})
         (dom/div
          (dom/props {:style {:font-weight "600"
                              :font-size   "1rem"}})
          (dom/text (t :config/create-pipeline tenant)))
         (dom/button
          (dom/props {:style {:background "none"
                              :border     "none"
                              :font-size  "1.5rem"
                              :cursor     "pointer"
                              :color      "#6b7280"}})
          (dom/text "×")
          (let [[tok _] (e/Token (dom/On "click" identity nil))]
            (when tok
              (reset! !show-modal nil)
              (tok)))))
        ;; Pipeline ID field
        (dom/div
         (dom/props {:style {:margin-bottom "1rem"}})
         (dom/label
          (dom/props {:style {:display       "block"
                              :font-weight   "500"
                              :margin-bottom "0.25rem"}})
          (dom/text (t :config/pipeline-id)))
         (dom/input
          (dom/props {:type        "text"
                      :placeholder "e.g., my-new-bot"
                      :value       pipeline-id
                      :style       input-style})
          (dom/On "input" #(reset! !pipeline-id (.. % -target -value)) nil)))
        ;; Pipeline Name field
        (dom/div
         (dom/props {:style {:margin-bottom "1rem"}})
         (dom/label
          (dom/props {:style {:display       "block"
                              :font-weight   "500"
                              :margin-bottom "0.25rem"}})
          (dom/text (t :config/display-name)))
         (dom/input
          (dom/props {:type        "text"
                      :placeholder "e.g., My New Bot"
                      :value       pipeline-name
                      :style       input-style})
          (dom/On "input" #(reset! !pipeline-name (.. % -target -value)) nil)))
        (dom/div
         (dom/props {:style {:display         "flex"
                             :gap             "0.5rem"
                             :justify-content "flex-end"
                             :margin-top      "1rem"}})
         (ModalButton (t :config/cancel) :secondary
                      (e/fn [] (reset! !show-modal nil)) nil)
         (ModalButton (t :config/create) :primary
                      (e/fn [] (on-create pipeline-id {:name pipeline-name}))
                      {:disabled (str/blank? pipeline-id)})))))))

(e/defn DuplicatePipelineModal [tenant source-pipeline-id on-duplicate !show-modal]
  "Modal for duplicating an existing pipeline."
  (e/client
   (let [!new-pipeline-id (atom (str source-pipeline-id "-copy"))
         new-pipeline-id  (e/watch !new-pipeline-id)]
     (Modal
      !show-modal
      nil
      (e/fn []
        (dom/div
         (dom/props {:style {:display         "flex"
                             :justify-content "space-between"
                             :align-items     "center"
                             :margin-bottom   "1rem"}})
         (dom/div
          (dom/props {:style {:font-weight "600"
                              :font-size   "1rem"}})
          (dom/text (t :config/duplicate-pipeline source-pipeline-id)))
         (dom/button
          (dom/props {:style {:background "none"
                              :border     "none"
                              :font-size  "1.5rem"
                              :cursor     "pointer"
                              :color      "#6b7280"}})
          (dom/text "×")
          (let [[tok _] (e/Token (dom/On "click" identity nil))]
            (when tok
              (reset! !show-modal nil)
              (tok)))))
        ;; New Pipeline ID field
        (dom/div
         (dom/props {:style {:margin-bottom "1rem"}})
         (dom/label
          (dom/props {:style {:display       "block"
                              :font-weight   "500"
                              :margin-bottom "0.25rem"}})
          (dom/text (t :config/new-pipeline-id)))
         (dom/input
          (dom/props {:type  "text"
                      :value new-pipeline-id
                      :style input-style})
          (dom/On "input" #(reset! !new-pipeline-id (.. % -target -value)) nil)))
        (dom/div
         (dom/props {:style {:display         "flex"
                             :gap             "0.5rem"
                             :justify-content "flex-end"
                             :margin-top      "1rem"}})
         (ModalButton (t :config/cancel) :secondary
                      (e/fn [] (reset! !show-modal nil)) nil)
         (ModalButton (t :config/duplicate) :primary
                      (e/fn [] (on-duplicate source-pipeline-id new-pipeline-id))
                      {:disabled (str/blank? new-pipeline-id)})))))))

;; -----------------------------------------------------------------------------
;; Badge Components
;; -----------------------------------------------------------------------------

(e/defn SensitivityBadge [sensitivity]
  (let [colors (get sensitivity-colors sensitivity {:bg   "#e5e7eb"
                                                    :text "#374151"})]
    (dom/span
     (dom/props {:style (merge badge-style {:background (:bg colors)
                                            :color      (:text colors)})})
     (dom/text (name (or sensitivity :unknown))))))

(e/defn ResolutionBadge [level]
  (let [colors (get resolution-colors level {:bg   "#e5e7eb"
                                             :text "#374151"})
        label  (case level
                 ;; New multi-dimensional labels (pipeline-first naming)
                 :pipeline-tenant-env "pipeline+tenant+env"
                 :pipeline-tenant "pipeline+tenant"
                 :pipeline-env "pipeline+env"
                 :pipeline "pipeline"
                 :tenant-env "tenant+env"
                 :tenant "tenant"
                 :environment "env"
                 :global "global"
                 "none")]
    (dom/span
     (dom/props {:style (merge badge-style {:background (:bg colors)
                                            :color      (:text colors)})})
     (dom/text label))))

#?(:clj
   (defn deployment-identity
     "Deployment identity of the JVM serving this UI. Server-only."
     []
     (deployment/collect))
   :cljs
   (defn deployment-identity [& _]
     (throw (ex-info "Deployment identity is only available on the JVM server" {}))))

(e/defn DeploymentIdentity
  "What is running, as four facts read out of this process.

   Deliberately verdict-free. There is no `up to date` badge and no mismatch
   warning, because this UI is served by the deployment it describes: a
   currency check made from here would be asking the suspect to confirm its own
   alibi. It shows the facts and lets the reader compare them with whatever
   they already believe is deployed."
  []
  (e/client
   (let [{:keys [version electric-version started-at built-at]} (e/server (deployment-identity))]
     (dom/div
      (dom/props {:style {:padding       "1rem"
                          :background    "#f8fafc"
                          :border        "1px solid #e2e8f0"
                          :border-radius "8px"
                          :color         "#0f172a"
                          :max-width     "720px"
                          :margin-bottom "1rem"}})
      (dom/div
       (dom/props {:style {:font-size "1rem" :font-weight "600" :margin-bottom "0.375rem"}})
       (dom/text "What is running"))
      (dom/div
       (dom/props {:style {:font-size "0.8125rem" :line-height "1.5"
                           :color "#475569" :margin-bottom "0.875rem"}})
       (dom/text (str "Read from this process and compared against nothing. The server commit is the git SHA "
                      "the deploy stamped into the container environment; the client build is what git describe "
                      "recorded when the browser bundle was compiled. The two come from different mechanisms, "
                      "so they can differ without either being wrong. Image built and process started "
                      "read together: an old build with a recent start is a restart rather than a deploy. "
                      "Redeploying an identical image is indistinguishable from a restart here, because "
                      "nothing in this process changes when it happens.")))
      (e/for [[label value]
              (e/diff-by first
                         [["Server commit \u2014 VERSION, set by the deploy" version]
                          ["Client build \u2014 electric-manifest.edn, set at build" electric-version]
                          ["Image built \u2014 BUILD_TIMESTAMP, set at build" built-at]
                          ["Process started" started-at]])]
        (dom/div
         (dom/props {:style {:display "flex" :gap "0.75rem" :align-items "baseline"
                             :padding "0.3125rem 0" :font-size "0.8125rem"
                             :border-top "1px solid #eef2f7"}})
         (dom/div
          (dom/props {:style {:color "#64748b" :flex "0 0 20rem"}})
          (dom/text label))
         (dom/div
          (dom/props {:style {:font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                              :word-break "break-all"}})
          (dom/text value))))))))

(e/defn DiagnosticsPanel []
  (dom/div
   (dom/props {:style {:padding "1rem" :max-width "100%"}})
   (DeploymentIdentity)
   (dom/div
    (dom/props {:style {:padding "1rem"
                        :background "#fff7ed"
                        :border "1px solid #fdba74"
                        :border-radius "8px"
                        :color "#9a3412"
                        :max-width "720px"}})
    (dom/div
     (dom/props {:style {:font-size "1rem"
                         :font-weight "600"
                         :margin-bottom "0.5rem"}})
     (dom/text "Diagnostics Temporarily Disabled"))
    (dom/div
     (dom/props {:style {:font-size "0.875rem"
                         :line-height "1.5"}})
     (dom/text "The Diagnostics Electric panel is temporarily disabled while we isolate a websocket/transit runtime failure. Dataset and pipeline metadata editing remain available from the Operator Console.")))))

;; =============================================================================
;; Operations Panel Components
;; =============================================================================

(def ops-panel-style styles/ops-panel-style)
(def ops-card-style styles/ops-card-style)
(def ops-card-title-style styles/ops-card-title-style)
(def preview-status-colors styles/preview-status-colors)

(e/defn PreviewStatusBadge [status count]
  "Badge showing preview status with count."
  (e/client
   (let [colors (get preview-status-colors status {:bg "#f3f4f6" :text "#6b7280" :border "#d1d5db"})]
     (dom/span
      (dom/props {:style {:display       "inline-flex"
                          :align-items   "center"
                          :gap           "0.25rem"
                          :padding       "0.125rem 0.5rem"
                          :border-radius "9999px"
                          :font-size     "0.75rem"
                          :font-weight   "500"
                          :background    (:bg colors)
                          :color         (:text colors)
                          :border        (str "1px solid " (:border colors))}})
      (dom/text (str (name status) ": " count))))))

(e/defn PreviewBanner [preview-data !preview-data on-apply]
  "Banner showing preview summary with apply/clear buttons."
  (e/client
   (let [defs   (:definitions preview-data)
         vals   (:values preview-data)
         audit  (:audit preview-data)]
     (dom/div
      (dom/props {:style {:display       "flex"
                          :align-items   "center"
                          :justify-content "space-between"
                          :gap           "1rem"
                          :padding       "0.75rem 1rem"
                          :background    "#eff6ff"
                          :border        "1px solid #bfdbfe"
                          :border-radius "6px"
                          :margin-bottom "1rem"}})
      ;; Preview stats
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.75rem" :align-items "center" :flex-wrap "wrap"}})
       (dom/span
        (dom/props {:style {:font-weight "600" :color "#1e40af"}})
        (dom/text "Preview:"))
       (when vals
         (dom/div
          (dom/props {:style {:display "flex" :gap "0.5rem"}})
          (when (pos? (or (:would-create vals) 0))
            (PreviewStatusBadge :create (:would-create vals)))
          (when (pos? (or (:would-overwrite vals) 0))
            (PreviewStatusBadge :update (:would-overwrite vals)))
          (when (pos? (or (:would-skip vals) 0))
            (PreviewStatusBadge :skip (:would-skip vals)))))
       (when defs
         (dom/span
          (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
          (dom/text (str "Definitions: " (:count defs 0)))))
       (when audit
         (dom/span
          (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
          (dom/text (str "Audit: " (:count audit 0))))))
      ;; Action buttons
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem"}})
       (dom/button
        (dom/props {:style (button-style :secondary :small)})
        (dom/text "Clear")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok (reset! !preview-data nil) (tok))))
       (when on-apply
         (dom/button
          (dom/props {:style (button-style :primary :small)})
          (dom/text "Apply")
          (let [[tok _] (e/Token (dom/On "click" identity nil))]
            (when tok (on-apply) (tok))))))))))

#?(:cljs
   (defn download-json-file
     "Trigger a browser download of JSON content."
     [json-str filename]
     (let [blob (js/Blob. #js [json-str] #js {:type "application/json"})
           url (js/URL.createObjectURL blob)
           a (js/document.createElement "a")]
       (set! (.-href a) url)
       (set! (.-download a) filename)
       (.click a)
       (js/URL.revokeObjectURL url))))

(e/defn ExportCard [tenants user-id !refresh-counter]
  "Export operation card with preview and download functionality."
  (e/client
   (let [!tenant        (atom nil)
         !include-audit (atom true)
         !password      (atom "")
         !message       (atom nil)
         !preview       (atom nil)
         tenant         (e/watch !tenant)
         include-audit  (e/watch !include-audit)
         password       (e/watch !password)
         message        (e/watch !message)
         preview        (e/watch !preview)]
     (dom/div
      (dom/props {:style ops-card-style})
      (dom/div
       (dom/props {:style ops-card-title-style})
       (dom/text "Export"))
      ;; Tenant selector
      (dom/div
       (dom/props {:style {:margin-bottom "0.5rem"}})
       (dom/label
        (dom/props {:style {:font-size     "0.75rem"
                            :color         "#6b7280"
                            :display       "block"
                            :margin-bottom "0.25rem"}})
        (dom/text "Scope:"))
       (dom/select
        (dom/props {:style (merge select-style {:width     "100%"
                                                :font-size "0.8rem"})
                    :value (or tenant "")})
        (dom/option (dom/props {:value ""}) (dom/text "Full Database"))
        (e/for [t (e/diff-by identity tenants)]
          (dom/option (dom/props {:value t}) (dom/text (str "Tenant: " t))))
        (dom/On "change" #(do (reset! !tenant (let [v (.. % -target -value)] (when (seq v) v)))
                              (reset! !preview nil)) nil)))
      ;; Include audit checkbox
      (dom/div
       (dom/props {:style {:margin-bottom "0.5rem"}})
       (dom/label
        (dom/props {:style {:display     "flex"
                            :align-items "center"
                            :gap         "0.5rem"
                            :font-size   "0.8rem"
                            :cursor      "pointer"}})
        (dom/input
         (dom/props {:type    "checkbox"
                     :checked include-audit})
         (dom/On "change" #(do (swap! !include-audit not)
                               (reset! !preview nil)) nil))
        (dom/text "Include audit history")))
      ;; Export password
      (dom/div
       (dom/props {:style {:margin-bottom "0.75rem"}})
       (dom/label
        (dom/props {:style {:font-size     "0.75rem"
                            :color         "#6b7280"
                            :display       "block"
                            :margin-bottom "0.25rem"}})
        (dom/text "Export password (for secrets):"))
       (dom/input
        (dom/props {:type        "password"
                    :value       password
                    :placeholder "Enter password..."
                    :style       (merge input-style {:font-size "0.8rem"
                                                     :padding   "0.375rem"})})
        (dom/On "input" #(reset! !password (.. % -target -value)) nil)))
      ;; Preview result
      (when preview
        (dom/div
         (dom/props {:style {:margin-bottom "0.5rem"
                             :padding       "0.5rem"
                             :background    "#f0fdf4"
                             :border        "1px solid #86efac"
                             :border-radius "4px"
                             :font-size     "0.7rem"}})
         (dom/div
          (dom/props {:style {:font-weight   "600"
                              :margin-bottom "0.25rem"
                              :color         "#166534"}})
          (dom/text "Export Preview"))
         (dom/div (dom/text (str "Definitions: " (get-in preview [:definitions :count]))))
         (dom/div (dom/text (str "Nodes: " (get-in preview [:nodes :count]))))
         (dom/div (dom/text (str "Node values: " (get-in preview [:node-values :count]))))
         (dom/div (dom/text (str "Datasets: " (get-in preview [:datasets :count]))))
         (dom/div (dom/text (str "Dataset pipelines: " (get-in preview [:dataset-pipelines :count]))))
         (dom/div (dom/text (str "Audit records: " (get-in preview [:audit :count]))))))
      ;; Message (for errors)
      (when message
        (dom/div
         (dom/props {:style {:margin-bottom "0.5rem"
                             :padding       "0.5rem"
                             :background    "#fef3c7"
                             :border-radius "4px"
                             :font-size     "0.75rem"}})
         (dom/text message)))
      ;; Buttons
      (dom/div
       (dom/props {:style {:display "flex"
                           :gap     "0.5rem"}})
       ;; Preview button
       (dom/button
        (dom/text "Preview")
        (when-some [tok (let [e       (dom/On "click" identity nil)
                              [t err] (e/Token e)]
                          (dom/props {:style     (merge (button-style :secondary :small) {:flex "1"})
                                      :aria-busy (some? t)
                                      :disabled  (some? t)})
                          t)]
          (let [result (e/server (e/Offload #(export-preview tenant include-audit user-id)))]
            (if (= :success (:status result))
              (do (reset! !preview (:data result))
                  (reset! !message nil))
              (do (reset! !message (str "Preview failed: " (:error result)))
                  (reset! !preview nil)))
            (tok))))
       ;; Export button
       (dom/button
        (dom/text "Export")
        (when-some [tok (let [e       (dom/On "click" identity nil)
                              [t err] (e/Token e)]
                          (dom/props {:style     (merge (button-style :primary :small) {:flex "1"})
                                      :aria-busy (some? t)
                                      :disabled  (or (str/blank? password) (some? t))})
                          t)]
          (let [result (e/server (e/Offload #(do-export! tenant include-audit password user-id)))]
            (if (= :success (:status result))
              (let [json-str (:data result)
                    filename (str "config-export-"
                                  (if tenant (str tenant "-") "")
                                  (.toISOString (js/Date.))
                                  ".json")]
                (download-json-file json-str filename)
                (reset! !message nil))
              (reset! !message (str "Export failed: " (:error result))))
            (tok)))))))))

#?(:cljs
   (defn handle-file-select [!filename !json-data e]
     (let [file (-> e .-target .-files (aget 0))]
       (when file
         (reset! !filename (.-name file))
         (let [reader (js/FileReader.)]
           (set! (.-onload reader) #(reset! !json-data (.-result reader)))
           (.readAsText reader file))))))

(e/defn ImportProgressBar [progress]
  "Render a progress bar for import operations."
  (e/client
   (let [percent (or (:percent progress) 0)
         phase (or (:phase progress) :idle)
         message (or (:message progress) "")]
     (dom/div
      (dom/props {:style {:margin-bottom "0.5rem"}})
      ;; Progress bar container
      (dom/div
       (dom/props {:style {:background "#e5e7eb"
                           :border-radius "4px"
                           :height "8px"
                           :overflow "hidden"
                           :margin-bottom "0.25rem"}})
       (dom/div
        (dom/props {:style {:background (if (= (:status progress) :complete) "#059669" "#3b82f6")
                            :height "100%"
                            :width (str percent "%")
                            :transition "width 0.2s ease-in-out"}})))
      ;; Status text
      (dom/div
       (dom/props {:style {:font-size "0.7rem" :color "#6b7280" :display "flex" :justify-content "space-between"}})
       (dom/span (dom/text message))
       (dom/span (dom/text (str percent "%"))))))))

(e/defn ImportResultsSummary [result]
  "Render import results summary."
  (e/client
   (let [defs (:definitions result)
         nodes (:nodes result)
         vals (:node-values result)
         datasets (:datasets result)
         dataset-pipelines (or (:dataset-pipelines result)
                               (:dataset-pipeline-result result))]
     (dom/div
      (dom/props {:style {:background "#f0fdf4"
                          :border "1px solid #86efac"
                          :border-radius "4px"
                          :padding "0.5rem"
                          :margin-bottom "0.5rem"
                          :font-size "0.75rem"}})
      (dom/div
       (dom/props {:style {:font-weight "600" :margin-bottom "0.25rem" :color "#166534"}})
       (dom/text "Import Complete"))
      (dom/div
       (dom/props {:style {:display "grid" :grid-template-columns "1fr 1fr" :gap "0.25rem"}})
       (dom/div
        (dom/text (str "Definitions: " (or (:created defs) 0) " imported")))
       (dom/div
        (dom/text (str "Nodes created: " (or (:created nodes) 0))))
       (dom/div
        (dom/text (str "Nodes updated: " (or (:updated nodes) 0))))
       (dom/div
        (dom/text (str "Node values created: " (or (:created vals) 0))))
       (dom/div
        (dom/text (str "Node values updated: " (or (:updated vals) 0))))
       (dom/div
        (dom/text (str "Node values skipped: " (or (:skipped vals) 0))))
       (dom/div
        (dom/text (str "Datasets created: " (or (:created datasets) 0))))
       (dom/div
        (dom/text (str "Dataset pipelines created: " (or (:created dataset-pipelines) 0))))
       (dom/div
        (dom/text (str "Dataset pipelines updated: " (or (:updated dataset-pipelines) 0))))
       (dom/div
        (dom/text (str "Dataset pipelines skipped: " (or (:skipped dataset-pipelines) 0))))
       (when (pos? (or (:missing-definition vals) 0))
         (dom/div
          (dom/props {:style {:color "#dc2626"}})
          (dom/text (str "Missing definitions: " (:missing-definition vals))))))))))

(e/defn ImportPreviewSummary [preview]
  "Render import preview summary."
  (e/client
   (let [defs (:definitions preview)
         nodes (:nodes preview)
         vals (:node-values preview)
         datasets (:datasets preview)
         dataset-pipelines (:dataset-pipelines preview)]
     (dom/div
      (dom/props {:style {:background "#eff6ff"
                          :border "1px solid #93c5fd"
                          :border-radius "4px"
                          :padding "0.5rem"
                          :margin-bottom "0.5rem"
                          :font-size "0.75rem"}})
      (dom/div
       (dom/props {:style {:font-weight "600" :margin-bottom "0.25rem" :color "#1e40af"}})
       (dom/text "Import Preview"))
      (dom/div
       (dom/props {:style {:display "grid" :grid-template-columns "1fr 1fr" :gap "0.25rem"}})
       (dom/div
        (dom/text (str "New definitions: " (or (:would-create defs) 0))))
       (dom/div
        (dom/text (str "Existing: " (or (:existing defs) 0))))
       (dom/div
        (dom/text (str "Nodes to create: " (or (:would-create nodes) 0))))
       (dom/div
        (dom/text (str "Nodes to update: " (or (:would-overwrite nodes) 0))))
       (dom/div
        (dom/text (str "Node values to create: " (or (:would-create vals) 0))))
       (dom/div
        (dom/text (str "Node values to update: " (or (:would-overwrite vals) 0))))
       (dom/div
        (dom/text (str "Node values to skip: " (or (:would-skip vals) 0))))
       (dom/div
        (dom/text (str "Datasets to create: " (or (:would-create datasets) 0))))
       (dom/div
        (dom/text (str "Dataset pipelines to create: " (or (:would-create dataset-pipelines) 0))))
       (dom/div
        (dom/text (str "Dataset pipelines to update: " (or (:would-overwrite dataset-pipelines) 0))))
       (dom/div
        (dom/text (str "Total node values: " (or (:total vals) 0)))))))))

(e/defn ImportCard [user-id !refresh-counter]
  "Import operation card with progress tracking and results display."
  (e/client
   (let [!json-data   (atom nil)
         !password    (atom "")
         !on-conflict (atom :skip)
         !state       (atom :idle)  ; :idle, :previewing, :preview-done, :importing, :complete, :error
         !preview     (atom nil)
         !result      (atom nil)
         !error       (atom nil)
         !filename    (atom nil)]
     ;; Watch all atoms for reactivity
     (let [json-data    (e/watch !json-data)
           password     (e/watch !password)
           on-conflict  (e/watch !on-conflict)
           state        (e/watch !state)
           preview      (e/watch !preview)
           result       (e/watch !result)
           error        (e/watch !error)
           filename     (e/watch !filename)]

       ;; Reactive server calls based on state
       ;; Note: case is used because Electric supports it and Preview works with it
       (case state
         :previewing
         (let [server-result (e/server
                              (e/Offload #(import-preview json-data on-conflict user-id)))]
           (e/client
            (if (= :success (:status server-result))
              (do (reset! !preview (:data server-result))
                  (reset! !state :preview-done))
              (do (reset! !error (or (:error server-result) "Preview failed"))
                  (reset! !state :error)))))

         :importing
         (let [server-result (e/server
                              (e/Offload #(do-import! json-data password on-conflict user-id)))]
           (e/client
            (if (= :success (:status server-result))
              (do (reset! !result (:data server-result))
                  (swap! !refresh-counter inc)
                  (reset! !state :complete))
              (do (reset! !error (or (:error server-result) "Import failed"))
                  (reset! !state :error)))))

         nil)

       (dom/div
        (dom/props {:style ops-card-style})
        (dom/div
         (dom/props {:style ops-card-title-style})
         (dom/text "Import"))

        ;; File input (disabled during import)
        (dom/div
         (dom/props {:style {:margin-bottom "0.5rem"}})
         (dom/label
          (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
          (dom/text "Select JSON file:"))
         (dom/input
          (dom/props {:type "file" :accept ".json"
                      :style {:font-size "0.75rem"}
                      :disabled (#{:previewing :importing} state)})
          (dom/On "change" (fn [e]
                             (handle-file-select !filename !json-data e)
                             ;; Reset state when new file selected
                             (reset! !state :idle)
                             (reset! !preview nil)
                             (reset! !result nil)
                             (reset! !error nil))
                  nil))
         (when filename
           (dom/span
            (dom/props {:style {:font-size "0.75rem" :color "#059669" :display "block" :margin-top "0.25rem"}})
            (dom/text (str "Loaded: " filename)))))

        ;; Import password
        (dom/div
         (dom/props {:style {:margin-bottom "0.5rem"}})
         (dom/label
          (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
          (dom/text "Import password:"))
         (dom/input
          (dom/props {:type "password"
                      :value password
                      :placeholder "Password used during export..."
                      :disabled (#{:previewing :importing} state)
                      :style (merge input-style {:font-size "0.8rem" :padding "0.375rem"})})
          (dom/On "input" #(reset! !password (.. % -target -value)) nil)))

        ;; Conflict mode
        (dom/div
         (dom/props {:style {:margin-bottom "0.75rem"}})
         (dom/label
          (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
          (dom/text "On conflict:"))
         (dom/select
          (dom/props {:style (merge select-style {:width "100%" :font-size "0.8rem"})
                      :value (name on-conflict)
                      :disabled (#{:previewing :importing} state)})
          (dom/option (dom/props {:value "skip"}) (dom/text "Skip existing"))
          (dom/option (dom/props {:value "overwrite"}) (dom/text "Overwrite"))
          (dom/On "change" #(reset! !on-conflict (keyword (.. % -target -value))) nil)))

        ;; Progress indicator (during import)
        (when (= state :importing)
          (dom/div
           (dom/props {:style {:margin-bottom "0.5rem" :padding "0.5rem" :background "#fef3c7" :border-radius "4px"}})
           (dom/div
            (dom/props {:style {:font-size "0.75rem" :color "#92400e"}})
            (dom/text "Importing... This may take a moment."))))

        ;; Previewing indicator
        (when (= state :previewing)
          (dom/div
           (dom/props {:style {:margin-bottom "0.5rem" :padding "0.5rem" :background "#dbeafe" :border-radius "4px"}})
           (dom/div
            (dom/props {:style {:font-size "0.75rem" :color "#1e40af"}})
            (dom/text "Generating preview..."))))

        ;; Preview results
        (when (and preview (= state :preview-done))
          (ImportPreviewSummary preview))

        ;; Import results
        (when (and result (= state :complete))
          (ImportResultsSummary result))

        ;; Error message
        (when error
          (dom/div
           (dom/props {:style {:margin-bottom "0.5rem"
                               :padding "0.5rem"
                               :background "#fef2f2"
                               :border "1px solid #fecaca"
                               :border-radius "4px"
                               :font-size "0.75rem"
                               :color "#dc2626"}})
           (dom/text (str "Error: " error))))

        ;; Buttons
        (dom/div
         (dom/props {:style {:display "flex" :gap "0.5rem"}})
         ;; Preview button
         (dom/button
          (dom/props {:style (merge (button-style :secondary :small) {:flex "1"})
                      :disabled (or (nil? json-data) (#{:previewing :importing} state))})
          (dom/text (if (= state :previewing) "Loading..." "Preview"))
          (let [[tok _] (e/Token (dom/On "click" identity nil))]
            (when tok (reset! !state :previewing) (tok))))

         ;; Import button (password validated server-side during re-encryption)
         (dom/button
          (dom/props {:style (merge (button-style :primary :small) {:flex "1"})
                      :disabled (or (nil? json-data)
                                    (#{:previewing :importing} state))})
          (dom/text (if (= state :importing) "Importing..." "Import"))
          (let [[tok _] (e/Token (dom/On "click" identity nil))]
            (when tok (reset! !state :importing) (tok))))

         ;; Reset button (after completion)
         (when (#{:complete :error :preview-done} state)
           (dom/button
            (dom/props {:style (merge (button-style :secondary :small) {:flex "0.5"})})
            (dom/text "Reset")
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (reset! !state :idle)
                (reset! !preview nil)
                (reset! !result nil)
                (reset! !error nil)
                (reset! !json-data nil)
                (reset! !filename nil)
                (reset! !password "")
                (tok)))))))))))

(e/defn CloneTenantSummary [{:keys [summary preview result]}]
  (e/client
   (let [clone-summary (:clone summary)
         preview-data preview
         result-data result]
     (dom/div
      (dom/props {:style {:background "#eff6ff"
                          :border "1px solid #93c5fd"
                          :border-radius "4px"
                          :padding "0.5rem"
                          :margin-bottom "0.5rem"
                          :font-size "0.75rem"}})
      (dom/div
       (dom/props {:style {:font-weight "600" :margin-bottom "0.25rem" :color "#1e40af"}})
       (dom/text "Clone Summary"))
      (dom/div (dom/text (str "Target tenant: " (:target-tenant summary))))
      (dom/div (dom/text (str "Nodes to clone: " (get-in clone-summary [:nodes :count] 0))))
      (dom/div (dom/text (str "Node values to clone: " (get-in clone-summary [:node-values :count] 0))))
      (dom/div (dom/text (str "Linked datasets: " (count (:linked-datasets clone-summary)))))
      (dom/div (dom/text (str "Linked dataset pipelines: " (count (:linked-dataset-pipelines clone-summary)))))
      (when preview-data
        (dom/div
         (dom/props {:style {:margin-top "0.5rem" :padding-top "0.5rem" :border-top "1px solid #bfdbfe"}})
         (dom/div (dom/text (str "Preview nodes: " (get-in preview-data [:nodes :total] 0))))
         (dom/div (dom/text (str "Preview node values: " (get-in preview-data [:node-values :total] 0))))))
      (when result-data
        (dom/div
         (dom/props {:style {:margin-top "0.5rem" :padding-top "0.5rem" :border-top "1px solid #bfdbfe"}})
         (dom/div (dom/text (str "Created nodes: " (get-in result-data [:nodes :created] 0))))
         (dom/div (dom/text (str "Created node values: " (get-in result-data [:node-values :created] 0))))))))))

(e/defn TenantRetirementPreviewSummary [preview]
  (e/client
   (dom/div
    (dom/props {:style {:background "#eff6ff"
                        :border "1px solid #93c5fd"
                        :border-radius "4px"
                        :padding "0.5rem"
                        :margin-bottom "0.5rem"
                        :font-size "0.75rem"}})
    (dom/div
     (dom/props {:style {:font-weight "600" :margin-bottom "0.25rem" :color "#1e40af"}})
     (dom/text "Retirement Preview"))
    (dom/div (dom/text (str "Tenant config key: " (:tenant-config-key preview))))
    (dom/div (dom/text (str "Removable tenants: " (count (:removable-tenants preview)))))
    (e/for [tenant-preview (e/diff-by :tenant (:tenants preview))]
      (dom/div
       (dom/props {:style {:margin-top "0.375rem"}})
       (dom/text
        (str (:tenant tenant-preview)
             ": "
             (when-let [root-summary (seq (:root-summaries tenant-preview))]
               (str (str/join ", "
                              (map (fn [{:keys [root nodes]}]
                                     (str nodes " " (name root) " node(s)"))
                                   root-summary))
                    ", "))
             (:conversations tenant-preview) " conversation(s)")))))))

(e/defn TenantRetirementResultSummary [result]
  (e/client
   (dom/div
    (dom/props {:style {:background "#fef2f2"
                        :border "1px solid #fecaca"
                        :border-radius "4px"
                        :padding "0.5rem"
                        :margin-bottom "0.5rem"
                        :font-size "0.75rem"
                        :color "#991b1b"}})
    (dom/div
     (dom/props {:style {:font-weight "600" :margin-bottom "0.25rem"}})
     (dom/text "Retirement Complete"))
    (dom/div (dom/text (str "Retired tenants: " (str/join ", " (:retired-tenants result)))))
    (dom/div (dom/text (str "Conversation strategy: " (name (:conversation-strategy result)))))
    (dom/div (dom/text (str "Remaining tenants: " (count (:remaining-tenants result)))))
    (when-let [repair-result (:repair-result result)]
      (dom/div (dom/text (str "Dangling parents repaired: " (:cleared-parent-count repair-result 0))))))))

(e/defn DeploymentTopologyResultSummary [result]
  (e/client
   (dom/div
    (dom/props {:style {:background "#f0fdf4"
                        :border "1px solid #86efac"
                        :border-radius "4px"
                        :padding "0.5rem"
                        :margin-bottom "0.5rem"
                        :font-size "0.75rem"}})
    (dom/div
     (dom/props {:style {:font-weight "600" :margin-bottom "0.25rem" :color "#166534"}})
     (dom/text "Topology Bootstrapped"))
    (dom/div (dom/text (str "Tenants: " (str/join ", " (map :tenant-id (:tenants result))))))
    (dom/div (dom/text (str "Digdir target pipelines: " (count (get-in result [:target-pipelines :digdir])))))
    (dom/div (dom/text (str "Public-sector target pipelines: " (count (get-in result [:target-pipelines :public-sector-knowledge])))))
    (dom/div (dom/text (str "Builtin agents ensured: " (count (get-in result [:agents :builtin])))))
    (dom/div (dom/text (str "Placeholder agents ensured: " (count (get-in result [:agents :placeholder]))))))))

(e/defn CloneTenantCard [tenants user-id !refresh-counter]
  "Clone tenant operation card aligned with the tenant/root/node config model."
  (e/client
   (let [!source-tenant (atom nil)
         !target-tenant (atom "")
         !exclude-ents  (atom false)
         !preview       (atom nil)
         !result        (atom nil)
         !error         (atom nil)
         source-tenant  (e/watch !source-tenant)
         target-tenant  (e/watch !target-tenant)
         exclude-ents   (e/watch !exclude-ents)
         preview        (e/watch !preview)
         result         (e/watch !result)
         error          (e/watch !error)]
     (dom/div
      (dom/props {:style ops-card-style})
      (dom/div
       (dom/props {:style ops-card-title-style})
       (dom/text "Clone Tenant"))
      (dom/div
       (dom/props {:style {:margin-bottom "0.5rem"
                           :padding "0.5rem"
                           :background "#eff6ff"
                           :border "1px solid #bfdbfe"
                           :border-radius "4px"
                           :font-size "0.75rem"
                           :color "#1e40af"}})
       (dom/text "Clones tenant-local platform/runtime/dataset trees into a new tenant. Linked dataset and pipeline records remain shared global metadata."))
      (dom/div
       (dom/props {:style {:margin-bottom "0.5rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
        (dom/text "Source tenant:"))
       (dom/select
        (dom/props {:style (merge select-style {:width "100%" :font-size "0.8rem"})
                    :value (or source-tenant "")})
        (dom/option (dom/props {:value ""}) (dom/text "Select..."))
        (e/for [t (e/diff-by identity tenants)]
          (dom/option (dom/props {:value t}) (dom/text t)))
        (dom/On "change" #(do (reset! !source-tenant (let [v (.. % -target -value)] (when (seq v) v)))
                              (reset! !preview nil)
                              (reset! !result nil)
                              (reset! !error nil))
                nil)))
      (dom/div
       (dom/props {:style {:margin-bottom "0.5rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
        (dom/text "Target tenant (new):"))
       (dom/input
        (dom/props {:type "text"
                    :value target-tenant
                    :placeholder "e.g., tenant-copy"
                    :style (merge input-style {:font-size "0.8rem" :padding "0.375rem"})})
        (dom/On "input" #(do (reset! !target-tenant (.. % -target -value))
                             (reset! !preview nil)
                             (reset! !result nil)
                             (reset! !error nil))
                nil)))
      (dom/div
       (dom/props {:style {:margin-bottom "0.75rem"}})
       (dom/label
        (dom/props {:style {:display "flex" :align-items "center" :gap "0.5rem" :font-size "0.8rem" :cursor "pointer"}})
        (dom/input
         (dom/props {:type "checkbox" :checked exclude-ents})
         (dom/On "change" #(do (swap! !exclude-ents not)
                               (reset! !preview nil)
                               (reset! !result nil)
                               (reset! !error nil))
                 nil))
        (dom/text "Exclude dataset pipeline nodes")))
      (when preview
        (CloneTenantSummary {:summary (:summary preview)
                             :preview (:preview preview)}))
      (when result
        (CloneTenantSummary {:summary (:summary result)
                             :result (:result result)}))
      (when error
        (dom/div
         (dom/props {:style {:margin-bottom "0.5rem"
                             :padding "0.5rem"
                             :background "#fef2f2"
                             :border "1px solid #fecaca"
                             :border-radius "4px"
                             :font-size "0.75rem"
                             :color "#dc2626"}})
         (dom/text (str "Error: " error))))
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem"}})
       (dom/button
        (dom/text "Preview")
        (when-some [tok (let [e (dom/On "click" identity nil)
                              [t _] (e/Token e)]
                          (dom/props {:style (merge (button-style :secondary :small) {:flex "1"})
                                      :aria-busy (some? t)
                                      :disabled (or (nil? source-tenant)
                                                    (str/blank? target-tenant)
                                                    (some? t))})
                          t)]
          (let [server-result (e/server (e/Offload #(preview-clone-tenant source-tenant target-tenant exclude-ents user-id)))]
            (if (= :success (:status server-result))
              (do (reset! !preview (:data server-result))
                  (reset! !result nil)
                  (reset! !error nil))
              (reset! !error (or (:error server-result) "Clone preview failed")))
            (tok))))
       (dom/button
        (dom/text "Clone")
        (when-some [tok (let [e (dom/On "click" identity nil)
                              [t _] (e/Token e)]
                          (dom/props {:style (merge (button-style :primary :small) {:flex "1"})
                                      :aria-busy (some? t)
                                      :disabled (or (nil? source-tenant)
                                                    (str/blank? target-tenant)
                                                    (some? t))})
                          t)]
          (let [server-result (e/server (e/Offload #(do-clone-tenant! source-tenant target-tenant exclude-ents user-id)))]
            (if (= :success (:status server-result))
              (do (reset! !result (:data server-result))
                  (reset! !preview nil)
                  (reset! !error nil)
                  (swap! !refresh-counter inc))
              (reset! !error (or (:error server-result) "Clone failed")))
            (tok)))))))))

(e/defn DeploymentTopologyCard [user-id !refresh-counter]
  "Bootstrap the refactor-aware target deployment topology."
  (e/client
   (let [!tenant-config-key (atom "default")
         !result            (atom nil)
         !error             (atom nil)
         tenant-config-key  (e/watch !tenant-config-key)
         result             (e/watch !result)
         error              (e/watch !error)]
     (dom/div
      (dom/props {:style ops-card-style})
      (dom/div
       (dom/props {:style ops-card-title-style})
       (dom/text "Bootstrap Target Topology"))
      (dom/div
       (dom/props {:style {:margin-bottom "0.75rem"
                           :padding "0.5rem"
                           :background "#eff6ff"
                           :border "1px solid #bfdbfe"
                           :border-radius "4px"
                           :font-size "0.75rem"
                           :color "#1e40af"}})
       (dom/text "Ensures the post-refactor deployment target tenants, dataset trees, pipelines, placeholder agents, and materialization defaults exist."))
      (dom/div
       (dom/props {:style {:margin-bottom "0.75rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
        (dom/text "Tenant config key:"))
       (dom/input
        (dom/props {:type "text"
                    :value tenant-config-key
                    :placeholder "default"
                    :style (merge input-style {:font-size "0.8rem" :padding "0.375rem"})})
        (dom/On "input" #(do (reset! !tenant-config-key (.. % -target -value))
                             (reset! !result nil)
                             (reset! !error nil))
                nil)))
      (when result
        (DeploymentTopologyResultSummary result))
      (when error
        (dom/div
         (dom/props {:style {:margin-bottom "0.5rem"
                             :padding "0.5rem"
                             :background "#fef2f2"
                             :border "1px solid #fecaca"
                             :border-radius "4px"
                             :font-size "0.75rem"
                             :color "#dc2626"}})
         (dom/text (str "Error: " error))))
      (dom/button
       (dom/text "Bootstrap")
       (when-some [tok (let [e (dom/On "click" identity nil)
                             [t _] (e/Token e)]
                         (dom/props {:style (merge (button-style :primary :small) {:width "100%"})
                                     :aria-busy (some? t)
                                     :disabled (some? t)})
                         t)]
         (let [server-result (e/server (e/Offload #(do-bootstrap-deployment-target-topology! tenant-config-key user-id)))]
           (if (= :success (:status server-result))
             (do (reset! !result (:data server-result))
                 (reset! !error nil)
                 (swap! !refresh-counter inc))
             (reset! !error (or (:error server-result) "Topology bootstrap failed")))
           (tok))))))))

(e/defn TenantRetirementCard [user-id !refresh-counter]
  "Preview and apply source-tenant retirement."
  (e/client
   (let [!tenant-input          (atom "altinn\naltinn-docs\nka")
         !tenant-config-key     (atom "default")
         !conversation-strategy (atom :retain)
         !preview               (atom nil)
         !result                (atom nil)
         !error                 (atom nil)
         tenant-input           (e/watch !tenant-input)
         tenant-config-key      (e/watch !tenant-config-key)
         conversation-strategy  (e/watch !conversation-strategy)
         preview                (e/watch !preview)
         result                 (e/watch !result)
         error                  (e/watch !error)
         parsed-tenants         (common/parse-tenant-list-input tenant-input)]
     (dom/div
      (dom/props {:style ops-card-style})
      (dom/div
       (dom/props {:style ops-card-title-style})
       (dom/text "Retire Source Tenants"))
      (dom/div
       (dom/props {:style {:margin-bottom "0.75rem"
                           :padding "0.5rem"
                           :background "#fef2f2"
                           :border "1px solid #fecaca"
                           :border-radius "4px"
                           :font-size "0.75rem"
                           :color "#991b1b"}})
       (dom/text "Destructive operation. Preview first, then retire only after target topology verification is complete."))
      (dom/div
       (dom/props {:style {:margin-bottom "0.5rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
        (dom/text "Source tenants:"))
       (dom/textarea
        (dom/props {:value tenant-input
                    :style (merge textarea-style {:min-height "90px" :font-size "0.75rem"})})
        (dom/On "input" #(do (reset! !tenant-input (.. % -target -value))
                             (reset! !preview nil)
                             (reset! !result nil)
                             (reset! !error nil))
                nil)))
      (dom/div
       (dom/props {:style {:margin-bottom "0.5rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
        (dom/text "Tenant config key:"))
       (dom/input
        (dom/props {:type "text"
                    :value tenant-config-key
                    :placeholder "default"
                    :style (merge input-style {:font-size "0.8rem" :padding "0.375rem"})})
        (dom/On "input" #(do (reset! !tenant-config-key (.. % -target -value))
                             (reset! !preview nil)
                             (reset! !result nil)
                             (reset! !error nil))
                nil)))
      (dom/div
       (dom/props {:style {:margin-bottom "0.75rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
        (dom/text "Conversation strategy:"))
       (dom/select
        (dom/props {:style (merge select-style {:width "100%" :font-size "0.8rem"})
                    :value (name conversation-strategy)})
        (dom/option (dom/props {:value "retain"}) (dom/text "Retain"))
        (dom/option (dom/props {:value "retarget"}) (dom/text "Retarget"))
        (dom/option (dom/props {:value "delete"}) (dom/text "Delete"))
        (dom/On "change" #(do (reset! !conversation-strategy (keyword (.. % -target -value)))
                              (reset! !result nil)
                              (reset! !error nil))
                nil)))
      (when preview
        (TenantRetirementPreviewSummary preview))
      (when result
        (TenantRetirementResultSummary result))
      (when error
        (dom/div
         (dom/props {:style {:margin-bottom "0.5rem"
                             :padding "0.5rem"
                             :background "#fef2f2"
                             :border "1px solid #fecaca"
                             :border-radius "4px"
                             :font-size "0.75rem"
                             :color "#dc2626"}})
         (dom/text (str "Error: " error))))
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem"}})
       (dom/button
        (dom/text "Preview")
        (when-some [tok (let [e (dom/On "click" identity nil)
                              [t _] (e/Token e)]
                          (dom/props {:style (merge (button-style :secondary :small) {:flex "1"})
                                      :aria-busy (some? t)
                                      :disabled (or (empty? parsed-tenants) (some? t))})
                          t)]
          (let [server-result (e/server (e/Offload #(preview-tenant-retirement tenant-input tenant-config-key user-id)))]
            (if (= :success (:status server-result))
              (do (reset! !preview (:data server-result))
                  (reset! !result nil)
                  (reset! !error nil))
              (reset! !error (or (:error server-result) "Retirement preview failed")))
            (tok))))
       (dom/button
        (dom/text "Retire")
        (when-some [tok (let [e (dom/On "click" identity nil)
                              [t _] (e/Token e)]
                          (dom/props {:style (merge (button-style :danger :small) {:flex "1"})
                                      :aria-busy (some? t)
                                      :disabled (or (empty? parsed-tenants) (some? t))})
                          t)]
          (let [server-result (e/server (e/Offload #(do-retire-source-tenants! tenant-input
                                                                            tenant-config-key
                                                                            conversation-strategy
                                                                            user-id)))]
            (if (= :success (:status server-result))
              (do (reset! !result (:data server-result))
                  (reset! !preview nil)
                  (reset! !error nil)
                  (swap! !refresh-counter inc))
              (reset! !error (or (:error server-result) "Tenant retirement failed")))
            (tok)))))))))

(e/defn DatabaseOperationsContent [tenants !refresh-counter user-id]
  (dom/div
   (dom/props {:style ops-panel-style})
   (dom/div
    (dom/props {:style {:font-size "1rem"
                        :font-weight "600"
                        :color "#1e40af"
                        :margin-bottom "1rem"}})
    (dom/text "Database Operations"))
   (dom/div
    (dom/props {:style {:display   "flex"
                        :gap       "1rem"
                        :flex-wrap "wrap"}})
    (ExportCard tenants user-id !refresh-counter)
    (ImportCard user-id !refresh-counter)
    (CloneTenantCard tenants user-id !refresh-counter)
    (DeploymentTopologyCard user-id !refresh-counter)
    (TenantRetirementCard user-id !refresh-counter))))

(e/defn DBManagement [tenants !refresh-counter is-admin user-id]
  (dom/div
   (dom/props {:style {:padding "1rem" :max-width "100%"}})
   (if is-admin
     (DatabaseOperationsContent tenants !refresh-counter user-id)
     (dom/div
      (dom/props {:style {:padding "1rem"
                          :background "#fef3c7"
                          :border "1px solid #fcd34d"
                          :border-radius "8px"
                          :color "#92400e"}})
      (dom/text "Database operations are only available to administrators.")))))

;; =============================================================================
;; Main Config Component
;; =============================================================================

(e/defn ConfigManagement [all-tenants !refresh-counter user-id]
  (dom/div
   #_(dom/props {:style {:padding "1rem" :max-width "100%"}})
   #_(ks/Heading {:level 2} (e/fn [] (dom/text (t :config/heading))))
   (ConfigInheritanceEditor all-tenants !refresh-counter user-id)))
;; =============================================================================
;; Main Tabbed Config UI
;; =============================================================================

(def tab-style styles/tab-style)
(def tab-active-style styles/tab-active-style)

(e/defn RoutedConfigTabButton [label tab-index active-tab set-active-tab!]
  "Tab button component with URL routing."
  (dom/button
   (dom/props {:style (if (= active-tab tab-index) tab-active-style tab-style)})
   (dom/text label)
   (let [[tok _] (e/Token (dom/On "click" identity nil))]
     (when tok
       (set-active-tab! tab-index)
       (tok)))))

(e/defn ConfigTabs []
  (e/client
   (let [!refresh-counter (atom 0)
         user-id (e/server (:user/id e/http-request))
         all-tenants (e/server (or (some-> (config-db/get-conn) deref config-db/list-tenants) []))
         is-admin (e/server (let [conn (config-db/get-conn)]
                              (boolean (and conn (perms/is-admin? @conn user-id)))))
         [active-tab set-active-tab!] (routing/UseRoutedTab :config 1)
         tab-labels [(t :config/tab-config)
                     (t :config/tab-db-management)
                     (t :config/tab-audit)
                     (t :config/tab-permissions)
                     (t :nav/api-keys)
                     "Skills"
                     "Diagnostics"
                     "Global Defaults"]]
     (dom/div
      ;; Tab bar
      (dom/div
       (dom/props {:style {:border-bottom "1px solid #e5e7eb"
                           :margin-bottom "1rem"
                           :display "flex"}})
       (e/for [[tab-index label] (e/diff-by first (map-indexed vector tab-labels))]
         (RoutedConfigTabButton label tab-index active-tab set-active-tab!)))

      ;; Tab content
      (case active-tab
        0 (ConfigManagement all-tenants !refresh-counter user-id)
        1 (DBManagement all-tenants !refresh-counter is-admin user-id)
        2 (AuditLog)
        3 (Permissions)
        4 (APIKeys)
        5 (SkillsUI)
        6 (DiagnosticsPanel)
        7 (GlobalDefaultsEditor user-id)
        (ConfigManagement all-tenants !refresh-counter user-id))))))

(e/defn Config []
  (ConfigTabs))
