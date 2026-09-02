(ns digdir.config.ui
  "Configuration management UI for admin interface.

   Features:
   - Browse config definitions by category
   - View/edit config values for tenant/environment
   - Tenant and environment selector
   - Resolution level indicators
   - Secret value masking
   - Audit log viewer
   - Permissions management"
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            [digdir.config.ui.audit :refer [AuditLog]]
            [digdir.config.ui.permissions :refer [Permissions]]
            [digdir.config.ui.api-keys :refer [APIKeys]]
            [digdir.pipeline.ui.pipelines :refer [Pipelines]]
            [digdir.pipeline.ui.skills :refer [SkillsUI]]
            [digdir.i18n :refer [t]]
            [digdir.ui.routing :as routing]
            #?(:clj [digdir.config.core :as cfg])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.config.crypto :as crypto])
            #?(:clj [digdir.config.permissions :as perms])
            #?(:clj [digdir.config.ops :as ops])
            #?(:clj [clojure.data.json :as json])
            #?(:clj [clojure.edn :as edn])
            #?(:clj [digdir.data.db :as db])))

;; =============================================================================
;; Styles
;; =============================================================================

(def table-style
  {:width "100%"
   :border-collapse "collapse"
   :font-size "0.875rem"})

(def th-style
  {:padding "0.75rem"
   :text-align "left"
   :background "#f9fafb"
   :border-bottom "2px solid #e5e7eb"
   :font-weight "600"
   :color "#374151"})

(def td-style
  {:padding "0.75rem"
   :border-bottom "1px solid #e5e7eb"
   :vertical-align "top"})

(def input-style
  {:width "100%"
   :padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :font-size "0.875rem"
   :font-family "monospace"})

(def select-style
  {:padding "0.5rem"
   :border "1px solid #d1d5db"
   :border-radius "4px"
   :background "white"
   :font-size "0.875rem"})

(def badge-style
  {:padding "0.125rem 0.5rem"
   :border-radius "9999px"
   :font-size "0.75rem"
   :font-weight "500"})

(def sensitivity-colors
  {:public {:bg "#dcfce7" :text "#166534"}
   :internal {:bg "#dbeafe" :text "#1e40af"}
   :admin-only {:bg "#fef3c7" :text "#92400e"}
   :secret {:bg "#fee2e2" :text "#991b1b"}})

(def resolution-colors
  "Colors for resolution level badges using multi-dimensional model.
   Organized from least specific (global) to most specific (entity+tenant+env)."
  {;; 0 dimensions
   :global {:bg "#f3f4f6" :text "#6b7280"}
   ;; 1 dimension
   :environment {:bg "#e0e7ff" :text "#3730a3"}
   :tenant {:bg "#dbeafe" :text "#1e40af"}
   :entity {:bg "#6ee7b7" :text "#064e3b"}
   ;; 2 dimensions
   :tenant-env {:bg "#dcfce7" :text "#166534"}
   :entity-env {:bg "#99f6e4" :text "#115e59"}
   :entity-tenant {:bg "#bfdbfe" :text "#1e3a8a"}
   ;; 3 dimensions
   :entity-tenant-env {:bg "#bbf7d0" :text "#14532d"}
   ;; Legacy support (for backwards compatibility during transition)
   :tenant-env-entity {:bg "#bbf7d0" :text "#14532d"}
   :tenant-entity {:bg "#bfdbfe" :text "#1e3a8a"}
   :specific {:bg "#dcfce7" :text "#166534"}})

;; Modal styles
(def modal-backdrop-style
  {:position   "fixed"
   :top        "0"
   :left       "0"
   :right      "0"
   :bottom     "0"
   :background "rgba(0,0,0,0.5)"
   :display    "flex"
   :align-items "center"
   :justify-content "center"
   :z-index    "1000"})

(def modal-content-style
  {:background     "white"
   :border-radius  "8px"
   :padding        "1.5rem"
   :max-width      "600px"
   :width          "90%"
   :max-height     "80vh"
   :overflow-y     "auto"})

(def textarea-style
  {:width       "100%"
   :padding     "0.5rem"
   :border      "1px solid #d1d5db"
   :border-radius "4px"
   :font-family "monospace"
   :font-size   "0.8rem"
   :resize      "vertical"
   :min-height  "150px"})

;; Button style factory
(def ^:private button-base-style
  {:border        "none"
   :border-radius "4px"
   :cursor        "pointer"})

(def ^:private button-variants
  {:primary   {:background "#3b82f6" :color "white"}
   :secondary {:background "#e5e7eb" :color "#374151"}
   :danger    {:background "#fee2e2" :color "#991b1b"}
   :success   {:background "#22c55e" :color "white"}})

(def ^:private button-sizes
  {:normal {:padding "0.5rem 1rem" :font-size "0.875rem" :border-radius "4px"}
   :small  {:padding "0.125rem 0.375rem" :font-size "0.625rem" :border-radius "2px"}})

(defn button-style
  "Generate button style.
   variant: :primary, :secondary, :danger, :success
   size: :normal (default), :small"
  ([variant] (button-style variant :normal))
  ([variant size]
   (merge button-base-style
          (get button-sizes size (:normal button-sizes))
          (get button-variants variant (:secondary button-variants)))))


;; =============================================================================
;; Helper Functions
;; =============================================================================

#?(:clj
   (defn format-timestamp [epoch-ms]
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

(defn generate-columns
  "Generate column definitions for inheritance table.
   Columns ordered from least to most specific (left to right).
   Within each dimension count, priority is: entity > tenant > environment.

   Order: Global | entity | tenant | env | entity:tenant | entity:env | tenant:env | entity:tenant:env

   selected-entities is a flat set of entity IDs (multi-dimensional model)."
  [selected-tenants selected-environments selected-entities]
  (let [tenants (sort selected-tenants)
        envs (sort selected-environments)
        ;; selected-entities is now a flat set
        entities (sort selected-entities)]
    (vec
     (concat
      ;; 0 dimensions: Global
      [{:key :global :label "Global" :level :global}]
      ;; 1 dimension: Entity only (highest priority in 1D)
      (for [entity entities]
        {:key [:entity entity] :label entity :level :entity})
      ;; 1 dimension: Tenant only
      (for [tenant tenants]
        {:key [:tenant tenant] :label tenant :level :tenant})
      ;; 1 dimension: Environment only (lowest priority in 1D)
      (for [env envs]
        {:key [:env env] :label env :level :environment})
      ;; 2 dimensions: Entity+Tenant (highest priority in 2D)
      (for [entity entities, tenant tenants]
        {:key [:entity-tenant entity tenant] :label (str entity ":" tenant) :level :entity-tenant})
      ;; 2 dimensions: Entity+Environment
      (for [entity entities, env envs]
        {:key [:entity-env entity env] :label (str entity ":" env) :level :entity-env})
      ;; 2 dimensions: Tenant+Environment (lowest priority in 2D)
      (for [tenant tenants, env envs]
        {:key [:tenant-env tenant env] :label (str tenant ":" env) :level :tenant-env})
      ;; 3 dimensions: Entity+Tenant+Environment
      (for [entity entities, tenant tenants, env envs]
        {:key [:entity-tenant-env entity tenant env] :label (str entity ":" tenant ":" env) :level :entity-tenant-env})))))

(defn find-effective-column-key
  "Find which column provides the effective value for a path."
  [path-values columns]
  (let [resolution-order (reverse (map :key columns))]
    (first (filter #(get path-values %) resolution-order))))

;; =============================================================================
;; Server-side Data Functions
;; =============================================================================

#?(:clj
   (defn- add-value-lengths
     "For encrypted paths, add :value-length to value entities by decrypting to get plaintext length.
      This allows the UI to show the correct number of asterisks without exposing the value."
     [path-values encrypted-paths master-key]
     (reduce-kv
      (fn [acc path level-values]
        (if (contains? encrypted-paths path)
          ;; This is an encrypted path - add lengths to each value entity
          (assoc acc path
                 (reduce-kv
                  (fn [level-acc level-key value-entity]
                    (if-let [raw-value (:config/value value-entity)]
                      (let [length (try
                                     (count (crypto/decrypt raw-value master-key))
                                     (catch Exception _ 0))]
                        (assoc level-acc level-key (assoc value-entity :value-length length)))
                      (assoc level-acc level-key value-entity)))
                  {}
                  level-values))
          ;; Not encrypted - pass through unchanged
          (assoc acc path level-values)))
      {}
      path-values)))

#?(:clj
   (defn get-inheritance-table-data-ui
     "Get config data organized for inheritance table view.
      The _refresh-counter parameter is unused but forces Electric to re-evaluate
      when the refresh counter changes."
     [selected-tenants selected-environments selected-entities user-id & [_refresh-counter]]
     (when-let [conn (config-db/get-conn)]
       (let [db                 @conn
             is-admin           (perms/is-admin? db user-id)
             master-key         (cfg/get-master-key)
             ;; Get all tenants from database
             all-tenants        (config-db/list-tenants db)
             ;; Get base data from db layer
             table-data         (config-db/get-inheritance-table-data
                                 db
                                 (set selected-tenants)
                                 (set selected-environments)
                                 selected-entities)
             ;; Build set of encrypted paths for efficient lookup
             encrypted-paths    (set (keep (fn [def]
                                             (when (:config-def/encrypted? def)
                                               (:config-def/path def)))
                                           (:definitions table-data)))
             ;; Add value lengths for encrypted values
             path-values-with-lengths (if (and (seq encrypted-paths) master-key)
                                        (add-value-lengths (:path-values table-data)
                                                           encrypted-paths
                                                           master-key)
                                        (:path-values table-data))
             ;; Add permission info to definitions
             defs-with-perms    (vec
                                 (for [def (:definitions table-data)]
                                   (let [path (:config-def/path def)]
                                     (assoc def
                                            :can-read (perms/can-access? db user-id path :read)
                                            :can-write (perms/can-access? db user-id path :write)))))
             ;; Get ALL entities (multi-dimensional model - entities are independent)
             all-entities (config-db/list-all-entities db)
             ;; Get entities for selected tenants (for backwards compatibility)
             entities-by-tenant (config-db/get-entities-by-tenant db selected-tenants)
             ;; Get entity names for display - resolve at entity-only level (no tenant)
             ;; This ensures we get names for all entities regardless of tenant association
             entity-names (config-db/get-entity-names db nil all-entities)
             ;; Get tenant names for display
             tenant-names (config-db/get-all-tenant-names db)]
         {:definitions        defs-with-perms
          :path-values        path-values-with-lengths
          :categories         (:categories table-data)
          :all-tenants        (or all-tenants [])
          :all-entities       all-entities
          :entities-by-tenant entities-by-tenant
          :entity-names       entity-names
          :tenant-names       tenant-names
          :is-admin           is-admin}))))

#?(:clj
   (defn parse-column-key
     "Parse a column key into [tenant environment entity] tuple.
      Handles both new multi-dimensional keys (entity-first) and legacy keys (tenant-first)."
     [column-key]
     (cond
       (= :global column-key) [nil nil nil]
       (keyword? column-key) [nil nil nil]
       :else
       (case (first column-key)
         ;; 1 dimension keys
         :env [nil (second column-key) nil]
         :tenant [(second column-key) nil nil]
         :entity [nil nil (second column-key)]
         ;; 2 dimension keys (new format: entity-first)
         :tenant-env [(second column-key) (nth column-key 2) nil]
         :entity-env [nil (nth column-key 2) (second column-key)]
         :entity-tenant [(nth column-key 2) nil (second column-key)]
         ;; 3 dimension keys (new format)
         :entity-tenant-env [(nth column-key 2) (nth column-key 3) (second column-key)]
         ;; Legacy support (tenant-first format)
         :tenant-entity [(second column-key) nil (nth column-key 2)]
         :tenant-env-entity [(second column-key) (nth column-key 2) (nth column-key 3)]
         [nil nil nil]))))

#?(:clj
   (defn parse-value-by-type
     "Safely parse a string value based on its type.
      Returns the parsed value or throws an exception for invalid input."
     [value value-type]
     (case value-type
       :string value
       :boolean (case (str/lower-case (str/trim value))
                  ("true" "1" "yes") true
                  ("false" "0" "no") false
                  (throw (ex-info "Invalid boolean value" {:value value})))
       :number (let [trimmed (str/trim value)]
                 (if (str/includes? trimmed ".")
                   (Double/parseDouble trimmed)
                   (Long/parseLong trimmed)))
       :edn (edn/read-string {:readers {}} value)
       ;; Default to string for unknown types
       value)))

#?(:clj
   (defn set-inheritance-value!
     "Set a config value at a specific inheritance level from InheritanceCell."
     [column-key path value value-type user-id user-email]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             master-key (cfg/get-master-key)
             [tenant environment entity] (parse-column-key column-key)
             parsed-value (parse-value-by-type value value-type)]
         ;; Check permission
         (when-not (perms/can-access? db user-id path :write)
           (throw (ex-info "Permission denied" {:path path})))
         ;; Set the value
         (config-db/set-value! conn
                               {:tenant tenant
                                :environment environment
                                :entity entity
                                :path path
                                :value parsed-value
                                :master-key master-key
                                :user-email user-email
                                :user-id user-id})
         :ok))))

#?(:clj
   (defn delete-inheritance-value!
     "Delete a config value at a specific inheritance level."
     [column-key path user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             [tenant environment entity] (parse-column-key column-key)]
         (when-not (perms/can-access? db user-id path :write)
           (throw (ex-info "Permission denied" {:path path})))
         (config-db/delete-value! conn tenant environment entity path)
         :ok))))

#?(:clj
   (defn create-entity-handler!
     "Create a new entity."
     [tenant entity-id properties user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             master-key (cfg/get-master-key)]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (config-db/create-entity! conn {:tenant tenant
                                         :environment nil
                                         :entity-id entity-id
                                         :properties properties
                                         :master-key master-key})
         :ok))))

#?(:clj
   (defn duplicate-entity-handler!
     "Duplicate an existing entity."
     [tenant source-entity-id new-entity-id user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             master-key (cfg/get-master-key)]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (config-db/duplicate-entity! conn {:tenant tenant
                                            :environment nil
                                            :source-entity-id source-entity-id
                                            :new-entity-id new-entity-id
                                            :master-key master-key})
         :ok))))

#?(:clj
   (defn soft-delete-entity-handler!
     "Soft-delete an entity."
     [tenant entity-id user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (config-db/soft-delete-entity! conn tenant entity-id)
         :ok))))

;; =============================================================================
;; Operations Handlers (Server-side)
;; =============================================================================

#?(:clj
   (defn export-preview
     "Get export preview for UI.
      Returns {:status :success :data result} or {:status :error :error message}."
     [tenant include-audit? user-id]
     (try
       (if-let [conn (config-db/get-conn)]
         (let [db @conn]
           (if-not (perms/is-admin? db user-id)
             {:status :error :error "Permission denied - admin required"}
             (let [opts {:dry-run? true :include-audit? include-audit?}
                   result (if tenant
                            (ops/export-tenant conn tenant opts)
                            (ops/export-full conn opts))]
               {:status :success :data result})))
         {:status :error :error "No database connection"})
       (catch Exception e
         {:status :error :error (.getMessage e)}))))

#?(:clj
   (defn do-export!
     "Execute export and return JSON string for download.
      Returns {:status :success :data json-string} or {:status :error :error message}."
     [tenant include-audit? export-password user-id]
     (try
       (if-let [conn (config-db/get-conn)]
         (let [db @conn
               master-key (cfg/get-master-key)]
           (if-not (perms/is-admin? db user-id)
             {:status :error :error "Permission denied - admin required"}
             (let [opts {:master-key master-key
                         :export-password export-password
                         :include-audit? include-audit?}
                   data (if tenant
                          (ops/export-tenant conn tenant opts)
                          (ops/export-full conn opts))
                   json-str (json/write-str data :escape-slash false)]
               {:status :success :data json-str})))
         {:status :error :error "No database connection"})
       (catch Exception e
         {:status :error :error (.getMessage e)}))))

#?(:clj
   (defn import-preview
     "Get import preview from JSON data string.
      Returns {:status :success :data result} or {:status :error :error message}."
     [json-str on-conflict user-id]
     (try
       (when-let [conn (config-db/get-conn)]
         (let [db @conn]
           (when-not (perms/is-admin? db user-id)
             (throw (ex-info "Permission denied - admin required" {})))
           (let [data (json/read-str json-str :key-fn keyword)
                 result (ops/import-data conn data {:on-conflict on-conflict :dry-run? true})]
             {:status :success :data result})))
       (catch Exception e
         {:status :error :error (or (ex-message e) "Preview failed")}))))

#?(:clj
   (defn do-import!
     "Execute import from JSON data string.
      Returns {:status :success :data result} or {:status :error :error message}.
      If progress-atom is provided, updates it with progress information."
     [json-str import-password on-conflict user-id & [progress-atom]]
     (try
       (let [conn (config-db/get-conn)]
         (if (nil? conn)
           {:status :error :error "No database connection"}
           (let [db @conn
                 master-key (cfg/get-master-key)
                 _ (when-not (perms/is-admin? db user-id)
                     (throw (ex-info "Permission denied - admin required" {})))
                 data (json/read-str json-str :key-fn keyword)
                 result (ops/import-data conn data {:master-key master-key
                                                    :export-password import-password
                                                    :on-conflict on-conflict
                                                    :progress-atom progress-atom})]
             {:status :success :data result})))
       (catch Exception e
         {:status :error :error (or (ex-message e) "Import failed")}))))

#?(:clj
   (defn clone-tenant-preview
     "Get clone tenant preview."
     [source-tenant target-tenant exclude-entities? user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             master-key (cfg/get-master-key)]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (ops/clone-tenant conn source-tenant target-tenant
                           {:master-key master-key
                            :exclude-entities? exclude-entities?
                            :dry-run? true})))))

#?(:clj
   (defn do-clone-tenant!
     "Execute tenant clone."
     [source-tenant target-tenant exclude-entities? user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             master-key (cfg/get-master-key)]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (ops/clone-tenant conn source-tenant target-tenant
                           {:master-key master-key
                            :exclude-entities? exclude-entities?})))))

#?(:clj
   (defn clone-env-preview
     "Get clone environment preview."
     [tenant source-env target-env exclude-entities? user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             master-key (cfg/get-master-key)]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (ops/clone-environment conn tenant source-env target-env
                                {:master-key master-key
                                 :exclude-entities? exclude-entities?
                                 :dry-run? true})))))

#?(:clj
   (defn do-clone-env!
     "Execute environment clone."
     [tenant source-env target-env exclude-entities? user-id]
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             master-key (cfg/get-master-key)]
         (when-not (perms/is-admin? db user-id)
           (throw (ex-info "Permission denied - admin required" {})))
         (ops/clone-environment conn tenant source-env target-env
                                {:master-key master-key
                                 :exclude-entities? exclude-entities?})))))

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
   (let [!edit-value (atom (or initial-value ""))
         edit-value  (e/watch !edit-value)
         show-modal  (e/watch !show-modal)
         vtype       (or value-type :string)] ;; Ensure value-type is never nil

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
            (dom/select
             (dom/props {:style select-style
                         :value edit-value})
             (dom/option (dom/props {:value "true"}) (dom/text "true"))
             (dom/option (dom/props {:value "false"}) (dom/text "false"))
             (dom/On "change" #(reset! !edit-value (.. % -target -value)) nil))

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
                        (e/fn [] (On-save edit-value)) nil))))))))

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

(e/defn NewEntityModal [tenant on-create !show-modal]
  "Modal for creating a new entity."
  (e/client
   (let [!entity-id   (atom "")
         !entity-name (atom "")
         entity-id    (e/watch !entity-id)
         entity-name  (e/watch !entity-name)]
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
          (dom/text (t :config/create-entity tenant)))
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
        ;; Entity ID field
        (dom/div
         (dom/props {:style {:margin-bottom "1rem"}})
         (dom/label
          (dom/props {:style {:display       "block"
                              :font-weight   "500"
                              :margin-bottom "0.25rem"}})
          (dom/text (t :config/entity-id)))
         (dom/input
          (dom/props {:type        "text"
                      :placeholder "e.g., my-new-bot"
                      :value       entity-id
                      :style       input-style})
          (dom/On "input" #(reset! !entity-id (.. % -target -value)) nil)))
        ;; Entity Name field
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
                      :value       entity-name
                      :style       input-style})
          (dom/On "input" #(reset! !entity-name (.. % -target -value)) nil)))
        (dom/div
         (dom/props {:style {:display         "flex"
                             :gap             "0.5rem"
                             :justify-content "flex-end"
                             :margin-top      "1rem"}})
         (ModalButton (t :config/cancel) :secondary
                      (e/fn [] (reset! !show-modal nil)) nil)
         (ModalButton (t :config/create) :primary
                      (e/fn [] (on-create entity-id {:name entity-name}))
                      {:disabled (str/blank? entity-id)})))))))

(e/defn DuplicateEntityModal [tenant source-entity-id on-duplicate !show-modal]
  "Modal for duplicating an existing entity."
  (e/client
   (let [!new-entity-id (atom (str source-entity-id "-copy"))
         new-entity-id  (e/watch !new-entity-id)]
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
          (dom/text (t :config/duplicate-entity source-entity-id)))
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
        ;; New Entity ID field
        (dom/div
         (dom/props {:style {:margin-bottom "1rem"}})
         (dom/label
          (dom/props {:style {:display       "block"
                              :font-weight   "500"
                              :margin-bottom "0.25rem"}})
          (dom/text (t :config/new-entity-id)))
         (dom/input
          (dom/props {:type  "text"
                      :value new-entity-id
                      :style input-style})
          (dom/On "input" #(reset! !new-entity-id (.. % -target -value)) nil)))
        (dom/div
         (dom/props {:style {:display         "flex"
                             :gap             "0.5rem"
                             :justify-content "flex-end"
                             :margin-top      "1rem"}})
         (ModalButton (t :config/cancel) :secondary
                      (e/fn [] (reset! !show-modal nil)) nil)
         (ModalButton (t :config/duplicate) :primary
                      (e/fn [] (on-duplicate source-entity-id new-entity-id))
                      {:disabled (str/blank? new-entity-id)})))))))

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
                 ;; New multi-dimensional labels (entity-first naming)
                 :entity-tenant-env "entity+tenant+env"
                 :entity-tenant "entity+tenant"
                 :entity-env "entity+env"
                 :entity "entity"
                 :tenant-env "tenant+env"
                 :tenant "tenant"
                 :environment "env"
                 :global "global"
                 ;; Legacy support (tenant-first naming)
                 :tenant-env-entity "entity+tenant+env"
                 :tenant-entity "entity+tenant"
                 :specific "tenant+env"
                 "none")]
    (dom/span
     (dom/props {:style (merge badge-style {:background (:bg colors)
                                            :color      (:text colors)})})
     (dom/text label))))

;; -----------------------------------------------------------------------------
;; Legend Components
;; -----------------------------------------------------------------------------

(def ^:private legend-container-style
  {:margin-top    "1rem"
   :padding       "1rem"
   :background    "#f9fafb"
   :border-radius "8px"
   :font-size     "0.75rem"
   :color         "#6b7280"})

(e/defn LegendContainer [Content]
  (e/client
   (dom/div
    (dom/props {:style legend-container-style})
    (dom/div
     (dom/props {:style {:font-weight "600" :margin-bottom "0.5rem"}})
     (dom/text (t :config/legend)))
    (Content))))

(e/defn InheritanceViewLegend []
  "Legend showing inheritance view indicators."
  (LegendContainer
   (e/fn []
     (dom/div
      (dom/props {:style {:display "flex" :gap "1rem" :flex-wrap "wrap" :align-items "center"}})
      (dom/span
       (dom/props {:style {:display       "inline-block"
                           :width         "1rem"
                           :height        "1rem"
                           :background    "#dcfce7"
                           :border        "1px solid #22c55e"
                           :margin-right  "0.25rem"}})
       (dom/text ""))
      (dom/span (dom/text "= Effective value (most specific)"))
      (dom/span
       (dom/props {:style {:margin-left "1rem"}})
       (dom/text "- = No value at this level (inherits from left)"))))))

;; =============================================================================
;; Operations Panel Components
;; =============================================================================

(def ops-panel-style
  {:background    "#f0f9ff"
   :border        "1px solid #bae6fd"
   :border-radius "8px"
   :padding       "1rem"
   :margin-bottom "1rem"})

(def ops-card-style
  {:background    "white"
   :border        "1px solid #e5e7eb"
   :border-radius "6px"
   :padding       "1rem"
   :min-width     "220px"
   :flex          "1"})

(def ops-card-title-style
  {:font-weight   "600"
   :font-size     "0.875rem"
   :color         "#1e40af"
   :margin-bottom "0.75rem"})

(def preview-status-colors
  {:create {:bg "#dcfce7" :text "#166534" :border "#22c55e"}
   :update {:bg "#fef3c7" :text "#92400e" :border "#f59e0b"}
   :skip   {:bg "#f3f4f6" :text "#6b7280" :border "#d1d5db"}
   :delete {:bg "#fee2e2" :text "#991b1b" :border "#ef4444"}})

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
         (dom/div (dom/text (str "Values: " (get-in preview [:values :count])
                                 " (" (get-in preview [:values :encrypted-count]) " encrypted)")))
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
         vals (:values result)]
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
       ;; Definitions
       (dom/div
        (dom/text (str "Definitions: " (or (:created defs) 0) " imported")))
       ;; Values
       (dom/div
        (dom/text (str "Values created: " (or (:created vals) 0))))
       (dom/div
        (dom/text (str "Values updated: " (or (:updated vals) 0))))
       (dom/div
        (dom/text (str "Values skipped: " (or (:skipped vals) 0))))
       (when (pos? (or (:missing-definition vals) 0))
         (dom/div
          (dom/props {:style {:color "#dc2626"}})
          (dom/text (str "Missing definitions: " (:missing-definition vals))))))))))

(e/defn ImportPreviewSummary [preview]
  "Render import preview summary."
  (e/client
   (let [defs (:definitions preview)
         vals (:values preview)]
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
       ;; Definitions
       (dom/div
        (dom/text (str "New definitions: " (or (:would-create defs) 0))))
       (dom/div
        (dom/text (str "Existing: " (or (:existing defs) 0))))
       ;; Values
       (dom/div
        (dom/text (str "Values to create: " (or (:would-create vals) 0))))
       (dom/div
        (dom/text (str "Would overwrite: " (or (:would-overwrite vals) 0))))
       (dom/div
        (dom/text (str "Would skip: " (or (:would-skip vals) 0))))
       (dom/div
        (dom/text (str "Total values: " (or (:total vals) 0)))))))))

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

(e/defn CloneTenantCard [tenants user-id !refresh-counter]
  "Clone tenant operation card - UI only, no server calls to avoid frame issues."
  (e/client
   (let [!source-tenant (atom nil)
         !target-tenant (atom "")
         !exclude-ents  (atom false)
         !message       (atom nil)
         source-tenant  (e/watch !source-tenant)
         target-tenant  (e/watch !target-tenant)
         exclude-ents   (e/watch !exclude-ents)
         message        (e/watch !message)]
     (dom/div
      (dom/props {:style ops-card-style})
      (dom/div
       (dom/props {:style ops-card-title-style})
       (dom/text "Clone Tenant"))
      ;; Source tenant
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
        (dom/On "change" #(reset! !source-tenant (let [v (.. % -target -value)] (when (seq v) v))) nil)))
      ;; Target tenant
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
        (dom/On "input" #(reset! !target-tenant (.. % -target -value)) nil)))
      ;; Exclude entities
      (dom/div
       (dom/props {:style {:margin-bottom "0.75rem"}})
       (dom/label
        (dom/props {:style {:display "flex" :align-items "center" :gap "0.5rem" :font-size "0.8rem" :cursor "pointer"}})
        (dom/input
         (dom/props {:type "checkbox" :checked exclude-ents})
         (dom/On "change" #(swap! !exclude-ents not) nil))
        (dom/text "Exclude entities")))
      ;; Message
      (when message
        (dom/div
         (dom/props {:style {:margin-bottom "0.5rem" :padding "0.5rem" :background "#fef3c7" :border-radius "4px" :font-size "0.75rem"}})
         (dom/text message)))
      ;; Buttons - placeholder
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem"}})
       (dom/button
        (dom/props {:style (merge (button-style :secondary :small) {:flex "1"})
                    :disabled (or (nil? source-tenant) (str/blank? target-tenant))})
        (dom/text "Preview")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok
            (reset! !message "Preview not yet implemented")
            (tok))))
       (dom/button
        (dom/props {:style (merge (button-style :primary :small) {:flex "1"})
                    :disabled (or (nil? source-tenant) (str/blank? target-tenant))})
        (dom/text "Clone")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok
            (reset! !message "Clone not yet implemented")
            (tok)))))))))

(e/defn CloneEnvCard [tenants user-id !refresh-counter]
  "Clone environment operation card - UI only, no server calls to avoid frame issues."
  (e/client
   (let [!tenant       (atom nil)
         !source-env   (atom nil)
         !target-env   (atom nil)
         !exclude-ents (atom false)
         !message      (atom nil)
         tenant        (e/watch !tenant)
         source-env    (e/watch !source-env)
         target-env    (e/watch !target-env)
         exclude-ents  (e/watch !exclude-ents)
         message       (e/watch !message)
         envs          ["prod" "staging" "test" "dev"]]
     (dom/div
      (dom/props {:style ops-card-style})
      (dom/div
       (dom/props {:style ops-card-title-style})
       (dom/text "Clone Environment"))
      ;; Tenant
      (dom/div
       (dom/props {:style {:margin-bottom "0.5rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
        (dom/text "Tenant:"))
       (dom/select
        (dom/props {:style (merge select-style {:width "100%" :font-size "0.8rem"})
                    :value (or tenant "")})
        (dom/option (dom/props {:value ""}) (dom/text "Select..."))
        (e/for [t (e/diff-by identity tenants)]
          (dom/option (dom/props {:value t}) (dom/text t)))
        (dom/On "change" #(reset! !tenant (let [v (.. % -target -value)] (when (seq v) v))) nil)))
      ;; Source env
      (dom/div
       (dom/props {:style {:margin-bottom "0.5rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
        (dom/text "Source environment:"))
       (dom/select
        (dom/props {:style (merge select-style {:width "100%" :font-size "0.8rem"})
                    :value (or source-env "")})
        (dom/option (dom/props {:value ""}) (dom/text "Select..."))
        (e/for [env (e/diff-by identity envs)]
          (dom/option (dom/props {:value env}) (dom/text env)))
        (dom/On "change" #(reset! !source-env (let [v (.. % -target -value)] (when (seq v) v))) nil)))
      ;; Target env
      (dom/div
       (dom/props {:style {:margin-bottom "0.5rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :display "block" :margin-bottom "0.25rem"}})
        (dom/text "Target environment:"))
       (dom/select
        (dom/props {:style (merge select-style {:width "100%" :font-size "0.8rem"})
                    :value (or target-env "")})
        (dom/option (dom/props {:value ""}) (dom/text "Select..."))
        (e/for [env (e/diff-by identity (remove #{source-env} envs))]
          (dom/option (dom/props {:value env}) (dom/text env)))
        (dom/On "change" #(reset! !target-env (let [v (.. % -target -value)] (when (seq v) v))) nil)))
      ;; Exclude entities
      (dom/div
       (dom/props {:style {:margin-bottom "0.75rem"}})
       (dom/label
        (dom/props {:style {:display "flex" :align-items "center" :gap "0.5rem" :font-size "0.8rem" :cursor "pointer"}})
        (dom/input
         (dom/props {:type "checkbox" :checked exclude-ents})
         (dom/On "change" #(swap! !exclude-ents not) nil))
        (dom/text "Exclude entities")))
      ;; Message
      (when message
        (dom/div
         (dom/props {:style {:margin-bottom "0.5rem" :padding "0.5rem" :background "#fef3c7" :border-radius "4px" :font-size "0.75rem"}})
         (dom/text message)))
      ;; Buttons - placeholder
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem"}})
       (dom/button
        (dom/props {:style (merge (button-style :secondary :small) {:flex "1"})
                    :disabled (or (nil? tenant) (nil? source-env) (nil? target-env))})
        (dom/text "Preview")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok
            (reset! !message "Preview not yet implemented")
            (tok))))
       (dom/button
        (dom/props {:style (merge (button-style :primary :small) {:flex "1"})
                    :disabled (or (nil? tenant) (nil? source-env) (nil? target-env))})
        (dom/text "Clone")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok
            (reset! !message "Clone not yet implemented")
            (tok)))))))))

(e/defn OperationsPanel [tenants !refresh-counter is-admin user-id]
  "Collapsible operations panel with import/export functionality for admins."
  (e/client
   (let [!expanded (atom false)
         expanded  (e/watch !expanded)]
     (when is-admin
       (dom/div
        (dom/props {:style {:margin-bottom "1rem"}})
        ;; Toggle button
        (dom/button
         (dom/props {:style {:display         "flex"
                             :align-items     "center"
                             :gap             "0.5rem"
                             :padding         "0.5rem 1rem"
                             :background      "#f0f9ff"
                             :border          "1px solid #bae6fd"
                             :border-radius   (if expanded "6px 6px 0 0" "6px")
                             :cursor          "pointer"
                             :font-size       "0.875rem"
                             :font-weight     "500"
                             :color           "#1e40af"
                             :width           "100%"
                             :justify-content "space-between"}})
         (dom/span (dom/text "Database Operations"))
         (dom/span (dom/text (if expanded "▲" "▼")))
         (let [[tok _] (e/Token (dom/On "click" identity nil))]
           (when tok (swap! !expanded not) (tok))))
        ;; Content panel - all four cards
        (dom/div
         (dom/props {:style (merge ops-panel-style
                                   {:border-top    "none"
                                    :border-radius "0 0 8px 8px"
                                    :display       (if expanded "block" "none")})})
         (dom/div
          (dom/props {:style {:display   "flex"
                              :gap       "1rem"
                              :flex-wrap "wrap"}})
          (ExportCard tenants user-id !refresh-counter)
          (ImportCard user-id !refresh-counter)
          (CloneTenantCard tenants user-id !refresh-counter)
          (CloneEnvCard tenants user-id !refresh-counter))))))))

(defn- tab-button-style
  "Generate tab button style based on selected state and size."
  [selected? size]
  (let [padding (if (= size :compact) "0.375rem 0.75rem" "0.5rem 1rem")
        font-size (if (= size :compact) "0.8rem" "0.875rem")]
    {:padding       padding
     :background    (if selected? "#3b82f6" "transparent")
     :color         (if selected? "white" "#6b7280")
     :border        "none"
     :border-radius "4px 4px 0 0"
     :cursor        "pointer"
     :font-size     font-size
     :font-weight   (if selected? "600" "400")}))

(e/defn CategoryTabButton [cat selected !selected-category size]
  "Single category tab button."
  (dom/button
   (dom/props {:style (tab-button-style (= cat selected) size)})
   (dom/text (if (= cat :all) "All" (name cat)))
   (let [[tok _] (e/Token (dom/On "click" identity nil))]
     (when tok (reset! !selected-category cat) (tok)))))

(e/defn CategoryTabs [categories !selected-category]
  (e/client
   (let [selected (e/watch !selected-category)
         all-categories (cons :all categories)]
     (dom/div
      (dom/props {:style {:display "flex"
                          :gap "0.25rem"
                          :margin-bottom "1rem"
                          :border-bottom "1px solid #e5e7eb"
                          :padding-bottom "0.5rem"}})
      (e/for [cat (e/diff-by identity all-categories)]
        (CategoryTabButton cat selected !selected-category :normal))))))

(e/defn CategoryTabsWithStats [categories !selected-category config-count group-count column-count]
  "Category tabs with inline stats on the right."
  (e/client
   (let [selected (e/watch !selected-category)
         all-categories (cons :all categories)]
     (dom/div
      (dom/props {:style {:display         "flex"
                          :justify-content "space-between"
                          :align-items     "center"
                          :margin-bottom   "0.75rem"
                          :border-bottom   "1px solid #e5e7eb"
                          :padding-bottom  "0.5rem"}})
      ;; Category tabs
      (dom/div
       (dom/props {:style {:display "flex"
                           :gap     "0.25rem"}})
       (e/for [cat (e/diff-by identity all-categories)]
         (CategoryTabButton cat selected !selected-category :compact)))
      ;; Stats (inline on the right)
      (dom/div
       (dom/props {:style {:font-size "0.75rem"
                           :color     "#9ca3af"}})
       (dom/text (str config-count " configs | " group-count " groups | " column-count " cols")))))))

;; =============================================================================
;; Routed Category Tab Components (URL-synced)
;; =============================================================================

(e/defn RoutedCategoryTabButton [cat selected set-category! size]
  "Single category tab button with URL routing."
  (dom/button
   (dom/props {:style (tab-button-style (= cat selected) size)})
   (dom/text (if (= cat :all) "All" (name cat)))
   (let [[tok _] (e/Token (dom/On "click" identity nil))]
     (when tok
       (set-category! (if (keyword? cat) (name cat) cat))
       (tok)))))

(e/defn RoutedCategoryTabs [categories selected set-category!]
  "Category tabs synced with URL routing."
  (e/client
   (let [all-categories (cons "all" (map name categories))
         selected-str (if (keyword? selected) (name selected) selected)]
     (dom/div
      (dom/props {:style {:display "flex"
                          :gap "0.25rem"
                          :margin-bottom "1rem"
                          :border-bottom "1px solid #e5e7eb"
                          :padding-bottom "0.5rem"}})
      (e/for [cat (e/diff-by identity all-categories)]
        (RoutedCategoryTabButton cat selected-str set-category! :normal))))))

(e/defn RoutedCategoryTabsWithStats [categories selected set-category! config-count group-count column-count]
  "Category tabs with inline stats, synced with URL routing."
  (e/client
   (let [all-categories (cons "all" (map name categories))
         selected-str (if (keyword? selected) (name selected) selected)]
     (dom/div
      (dom/props {:style {:display         "flex"
                          :justify-content "space-between"
                          :align-items     "center"
                          :margin-bottom   "0.75rem"
                          :border-bottom   "1px solid #e5e7eb"
                          :padding-bottom  "0.5rem"}})
      ;; Category tabs
      (dom/div
       (dom/props {:style {:display "flex"
                           :gap     "0.25rem"}})
       (e/for [cat (e/diff-by identity all-categories)]
         (RoutedCategoryTabButton cat selected-str set-category! :compact)))
      ;; Stats (inline on the right)
      (dom/div
       (dom/props {:style {:font-size "0.75rem"
                           :color     "#9ca3af"}})
       (dom/text (str config-count " configs | " group-count " groups | " column-count " cols")))))))

;; =============================================================================
;; Inheritance View Components
;; =============================================================================

(def checkbox-style
  {:margin-right "0.5rem"
   :cursor       "pointer"})

;; Compact dropdown multi-select styles
(def dropdown-button-style
  {:padding       "0.375rem 0.75rem"
   :background    "white"
   :border        "1px solid #d1d5db"
   :border-radius "4px"
   :cursor        "pointer"
   :font-size     "0.8rem"
   :display       "flex"
   :align-items   "center"
   :gap           "0.375rem"
   :min-width     "100px"})

(def dropdown-panel-style
  {:position      "absolute"
   :top           "100%"
   :left          "0"
   :background    "white"
   :border        "1px solid #d1d5db"
   :border-radius "4px"
   :box-shadow    "0 4px 6px -1px rgba(0,0,0,0.1)"
   :z-index       "100"
   :min-width     "140px"
   :max-height    "200px"
   :overflow-y    "auto"
   :padding       "0.25rem 0"})

(def inheritance-cell-style
  {:padding        "0.375rem 0.5rem"
   :border-bottom  "1px solid #e5e7eb"
   :border-right   "1px solid #f3f4f6"
   :font-size      "0.75rem"
   :font-family    "monospace"
   :max-width      "120px"
   :min-width      "80px"
   :overflow       "hidden"
   :text-overflow  "ellipsis"
   :white-space    "nowrap"
   :vertical-align "middle"})

(def effective-cell-style
  {:background  "#dcfce7"
   :font-weight "600"})

(def ^:private checkbox-item-style
  {:display       "flex"
   :align-items   "center"
   :cursor        "pointer"
   :margin-bottom "0.25rem"
   :font-size     "0.875rem"})

(e/defn CheckboxItemBase [label checked? on-toggle]
  "Base checkbox item component with label."
  (dom/label
   (dom/props {:style checkbox-item-style})
   (dom/input
    (dom/props {:type    "checkbox"
                :checked checked?
                :style   checkbox-style})
    (dom/On "change" (fn [_] (on-toggle)) nil))
   (dom/text label)))

(e/defn CheckboxItem [item selected-set !selected-set]
  "Checkbox item for simple set-based selection."
  (e/client
   (CheckboxItemBase
    item
    (contains? selected-set item)
    #(swap! !selected-set
            (fn [s] (if (contains? s item)
                      (disj s item)
                      (conj s item)))))))

(e/defn EntityCheckboxItem [tenant entity-id selected-entities !selected-entities]
  "Checkbox item for entity selection (nested map structure)."
  (e/client
   (let [tenant-entities (get selected-entities tenant #{})]
     (CheckboxItemBase
      entity-id
      (contains? tenant-entities entity-id)
      #(swap! !selected-entities
              (fn [m]
                (let [current (get m tenant #{})]
                  (assoc m tenant
                         (if (contains? current entity-id)
                           (disj current entity-id)
                           (conj current entity-id))))))))))

;; -----------------------------------------------------------------------------
;; Compact Dropdown Multi-Select Components
;; -----------------------------------------------------------------------------

(def ^:private count-badge-style
  {:background    "#3b82f6"
   :color         "white"
   :padding       "0.125rem 0.375rem"
   :border-radius "9999px"
   :font-size     "0.7rem"
   :font-weight   "600"})

(def ^:private dropdown-arrow-style
  {:color     "#9ca3af"
   :font-size "0.7rem"})

(def ^:private dropdown-item-style
  {:display     "flex"
   :align-items "center"
   :padding     "0.375rem 0.75rem"
   :cursor      "pointer"
   :font-size   "0.8rem"})

(e/defn CountBadge [n]
  "Displays a count badge (pill-shaped)."
  (when (pos? n)
    (dom/span
     (dom/props {:style count-badge-style})
     (dom/text (str n)))))

(e/defn DropdownButton [label count open? !open]
  "Dropdown trigger button with label, count badge, and arrow."
  (dom/button
   (dom/props {:style (merge dropdown-button-style
                             (when (pos? count) {:border-color "#3b82f6"}))})
   (dom/span
    (dom/props {:style {:color "#374151"}})
    (dom/text label))
   (CountBadge count)
   (dom/span
    (dom/props {:style dropdown-arrow-style})
    (dom/text (if open? "▲" "▼")))
   (let [[tok _] (e/Token (dom/On "click" identity nil))]
     (when tok (swap! !open not) (tok)))))

(e/defn DropdownCheckboxItem [item checked? on-toggle]
  "Single checkbox item in a dropdown."
  (dom/label
   (dom/props {:style (merge dropdown-item-style
                             (when checked? {:background "#eff6ff"}))})
   (dom/input
    (dom/props {:type    "checkbox"
                :checked checked?
                :style   {:margin-right "0.5rem"}})
    (dom/On "change" (fn [_] (on-toggle)) nil))
   (dom/text item)))

(e/defn DropdownMultiSelect [label items selected-set !selected-set]
  "Compact dropdown multi-select component."
  (e/client
   (let [!open (atom false)
         open  (e/watch !open)
         cnt   (count selected-set)]
     (dom/div
      (dom/props {:style {:position "relative"}})
      (DropdownButton label cnt open !open)
      (when open
        (dom/div
         (dom/props {:style dropdown-panel-style})
         (e/for [item (e/diff-by identity items)]
           (DropdownCheckboxItem
            item
            (contains? selected-set item)
            #(swap! !selected-set
                    (fn [s] (if (contains? s item)
                              (disj s item)
                              (conj s item))))))))))))

(e/defn EmptyState [message]
  "Displays an empty state message."
  (dom/div
   (dom/props {:style {:padding    "0.5rem 0.75rem"
                       :color      "#9ca3af"
                       :font-size  "0.8rem"
                       :font-style "italic"}})
   (dom/text message)))

(e/defn EntityDropdownMultiSelect [tenant entities entity-names selected-entities !selected-entities on-new on-duplicate on-delete is-admin]
  "Compact dropdown for entity selection with management actions."
  (e/client
   (let [!open           (atom false)
         open            (e/watch !open)
         tenant-entities (get selected-entities tenant #{})
         cnt             (count tenant-entities)]
     (dom/div
      (dom/props {:style {:position "relative"}})
      (DropdownButton (t :config/entities-for tenant) cnt open !open)
      (when open
        (dom/div
         (dom/props {:style (merge dropdown-panel-style {:min-width "180px"})})
         ;; New entity button (admin only)
         (when is-admin
           (dom/div
            (dom/props {:style {:padding       "0.375rem 0.75rem"
                                :border-bottom "1px solid #e5e7eb"}})
            (dom/button
             (dom/props {:style (merge (button-style :success :small)
                                       {:width "100%"})})
             (dom/text (t :config/new-entity-btn))
             (let [[tok _] (e/Token (dom/On "click" identity nil))]
               (when tok
                 (on-new tenant)
                 (reset! !open false)
                 (tok))))))
         ;; Entity list
         (if (seq entities)
           (e/for [entity-id (e/diff-by identity entities)]
             (let [checked (contains? tenant-entities entity-id)]
               (dom/div
                (dom/props {:style (merge dropdown-item-style
                                          {:background (when checked "#eff6ff")})})
                (dom/label
                 (dom/props {:style {:display     "flex"
                                     :align-items "center"
                                     :cursor      "pointer"
                                     :font-size   "0.8rem"
                                     :flex        "1"}})
                 (dom/input
                  (dom/props {:type    "checkbox"
                              :checked checked
                              :style   {:margin-right "0.5rem"}})
                  (dom/On "change"
                          #(swap! !selected-entities
                                  (fn [m]
                                    (let [current (get m tenant #{})]
                                      (assoc m tenant
                                             (if (contains? current entity-id)
                                               (disj current entity-id)
                                               (conj current entity-id))))))
                          nil))
                 (dom/text (or (get entity-names entity-id) entity-id)))
                ;; Action buttons (admin only)
                (when is-admin
                  (dom/div
                   (dom/props {:style {:display "flex" :gap "0.125rem"}})
                   (dom/button
                    (dom/props {:style (merge (button-style :secondary :small)
                                              {:background "#dbeafe"
                                               :color      "#1e40af"})
                                :title "Duplicate"})
                    (dom/text "dup")
                    (let [[tok _] (e/Token (dom/On "click" identity nil))]
                      (when tok
                        (on-duplicate tenant entity-id)
                        (reset! !open false)
                        (tok))))
                   (dom/button
                    (dom/props {:style (merge (button-style :danger :small))
                                :title "Delete"})
                    (dom/text "del")
                    (let [[tok _] (e/Token (dom/On "click" identity nil))]
                      (when tok
                        (when (js/confirm (str "Delete entity '" entity-id "'?"))
                          (on-delete tenant entity-id))
                        (reset! !open false)
                        (tok))))))))))
         (EmptyState "No entities")))))))

(def ^:private filter-panel-style
  {:background    "#f9fafb"
   :border        "1px solid #e5e7eb"
   :border-radius "6px"
   :margin-bottom "0.75rem"})

(def ^:private filter-header-style
  {:display       "flex"
   :align-items   "center"
   :justify-content "space-between"
   :padding       "0.5rem 0.75rem"
   :cursor        "pointer"})

(def ^:private filter-content-style
  {:display     "flex"
   :gap         "1rem"
   :padding     "0.75rem"
   :border-top  "1px solid #e5e7eb"})

(def ^:private filter-column-style
  {:flex        "1"
   :min-width   "150px"
   :max-width   "250px"})

(def ^:private filter-list-style
  {:max-height "200px"
   :overflow-y "auto"
   :background "white"
   :border     "1px solid #e5e7eb"
   :border-radius "4px"
   :padding    "0.25rem 0"})

(def ^:private filter-label-style
  {:font-weight   "600"
   :font-size     "0.75rem"
   :color         "#374151"
   :margin-bottom "0.375rem"
   :display       "block"})

(e/defn FilterCheckboxItem [item checked? on-toggle]
  "Checkbox item for filter lists."
  (dom/label
   (dom/props {:style (merge dropdown-item-style
                             (when checked? {:background "#eff6ff"}))})
   (dom/input
    (dom/props {:type    "checkbox"
                :checked checked?
                :style   {:margin-right "0.5rem"}})
    (dom/On "change" (fn [_] (on-toggle)) nil))
   (dom/text item)))

(e/defn FilterCheckboxItemWithName [item display-name checked? on-toggle]
  "Checkbox item for filter lists with separate ID and display name."
  (dom/label
   (dom/props {:style (merge dropdown-item-style
                             (when checked? {:background "#eff6ff"}))})
   (dom/input
    (dom/props {:type    "checkbox"
                :checked checked?
                :style   {:margin-right "0.5rem"}})
    (dom/On "change" (fn [_] (on-toggle)) nil))
   (dom/text display-name)))

(e/defn FilterColumn [label items selected-set !selected-set]
  "A single filter column with label and scrollable checkbox list."
  (e/client
   (dom/div
    (dom/props {:style filter-column-style})
    (dom/span
     (dom/props {:style filter-label-style})
     (dom/text label)
     (when (pos? (count selected-set))
       (dom/span
        (dom/props {:style {:margin-left   "0.5rem"
                            :background    "#3b82f6"
                            :color         "white"
                            :padding       "0.125rem 0.375rem"
                            :border-radius "9999px"
                            :font-size     "0.625rem"
                            :font-weight   "600"}})
        (dom/text (count selected-set)))))
    (dom/div
     (dom/props {:style filter-list-style})
     (if (empty? items)
       (dom/div
        (dom/props {:style {:padding    "0.5rem 0.75rem"
                            :color      "#9ca3af"
                            :font-size  "0.8rem"
                            :font-style "italic"}})
        (dom/text "None available"))
       (e/for [item (e/diff-by identity items)]
         (FilterCheckboxItem
          item
          (contains? selected-set item)
          #(swap! !selected-set
                  (fn [s] (if (contains? s item)
                            (disj s item)
                            (conj s item)))))))))))

(e/defn FilterColumnWithNames [label items names-map selected-set !selected-set]
  "A filter column that displays friendly names from a map.
   items - collection of IDs
   names-map - map of ID -> display name"
  (e/client
   (dom/div
    (dom/props {:style filter-column-style})
    (dom/span
     (dom/props {:style filter-label-style})
     (dom/text label)
     (when (pos? (count selected-set))
       (dom/span
        (dom/props {:style {:margin-left "0.5rem"
                            :background  "#3b82f6"
                            :color       "white"
                            :padding     "0.125rem 0.375rem"
                            :border-radius "9999px"
                            :font-size   "0.625rem"
                            :font-weight "600"}})
        (dom/text (count selected-set)))))
    (dom/div
     (dom/props {:style filter-list-style})
     (if (empty? items)
       (dom/div
        (dom/props {:style {:padding    "0.5rem 0.75rem"
                            :color      "#9ca3af"
                            :font-size  "0.8rem"
                            :font-style "italic"}})
        (dom/text "None available"))
       (e/for [item (e/diff-by identity (sort items))]
         (FilterCheckboxItemWithName
          item
          (get names-map item item)  ; Fall back to ID if no name
          (contains? selected-set item)
          #(swap! !selected-set
                  (fn [s] (if (contains? s item)
                            (disj s item)
                            (conj s item)))))))))))

(e/defn EntityActionButtons [tenant entity-id on-duplicate on-delete]
  "Action buttons for entity management (duplicate, delete)."
  (e/client
   (dom/div
    (dom/props {:style {:display     "inline-flex"
                        :gap         "0.125rem"
                        :margin-left "0.25rem"}})
    ;; Duplicate button
    (dom/button
     (dom/props {:style (merge (button-style :secondary :small)
                               {:background "#dbeafe"
                                :color      "#1e40af"})
                 :title "Duplicate entity"})
     (dom/text "dup")
     (let [[tok _] (e/Token (dom/On "click" identity nil))]
       (when tok
         (on-duplicate tenant entity-id)
         (tok))))
    ;; Delete button
    (dom/button
     (dom/props {:style (button-style :danger :small)
                 :title "Delete entity"})
     (dom/text "del")
     (let [[tok _] (e/Token (dom/On "click" identity nil))]
       (when tok
         (when (js/confirm (str "Delete entity '" entity-id "'? This will soft-delete all its configuration."))
           (on-delete tenant entity-id))
         (tok)))))))

(e/defn EntityFilterColumn [tenant entities entity-names selected-entities !selected-entities on-new on-duplicate on-delete is-admin]
  "Entity filter column with management actions."
  (e/client
   (let [tenant-entities (get selected-entities tenant #{})]
     (dom/div
      (dom/props {:style filter-column-style})
      (dom/span
       (dom/props {:style filter-label-style})
       (dom/text (str tenant " Entities"))
       (when (pos? (count tenant-entities))
         (dom/span
          (dom/props {:style {:margin-left "0.5rem"
                              :background  "#3b82f6"
                              :color       "white"
                              :padding     "0.125rem 0.375rem"
                              :border-radius "9999px"
                              :font-size   "0.625rem"
                              :font-weight "600"}})
          (dom/text (count tenant-entities)))))
      (dom/div
       (dom/props {:style filter-list-style})
       ;; New entity button (admin only)
       (when is-admin
         (dom/div
          (dom/props {:style {:padding       "0.375rem 0.75rem"
                              :border-bottom "1px solid #e5e7eb"}})
          (dom/button
           (dom/props {:style (merge (button-style :success :small)
                                     {:width "100%"})})
           (dom/text (t :config/new-entity-btn))
           (let [[tok _] (e/Token (dom/On "click" identity nil))]
             (when tok
               (on-new tenant)
               (tok))))))
       ;; Entity list
       (if (empty? entities)
         (dom/div
          (dom/props {:style {:padding    "0.5rem 0.75rem"
                              :color      "#9ca3af"
                              :font-size  "0.8rem"
                              :font-style "italic"}})
          (dom/text "No entities"))
         (e/for [entity-id (e/diff-by identity entities)]
           (let [entity-name (get entity-names entity-id)
                 display-name (if entity-name (str entity-id " (" entity-name ")") entity-id)]
             (dom/div
              (dom/props {:style {:display     "flex"
                                  :align-items "center"
                                  :justify-content "space-between"}})
              (dom/label
               (dom/props {:style (merge dropdown-item-style
                                         {:flex "1"}
                                         (when (contains? tenant-entities entity-id)
                                           {:background "#eff6ff"}))})
               (dom/input
                (dom/props {:type    "checkbox"
                            :checked (contains? tenant-entities entity-id)
                            :style   {:margin-right "0.5rem"}})
                (dom/On "change"
                        (fn [_]
                          (swap! !selected-entities
                                 (fn [m]
                                   (let [current (get m tenant #{})]
                                     (assoc m tenant
                                            (if (contains? current entity-id)
                                              (disj current entity-id)
                                              (conj current entity-id)))))))
                        nil))
               (dom/text display-name))
              (when is-admin
                (EntityActionButtons tenant entity-id on-duplicate on-delete)))))))))))

(e/defn AllEntitiesFilterColumn [all-entities entity-names selected-entities !selected-entities on-new is-admin]
  "Entity filter column showing all entities across all tenants.
   selected-entities is a flat set of entity IDs."
  (e/client
   (dom/div
    (dom/props {:style filter-column-style})
    (dom/span
     (dom/props {:style filter-label-style})
     (dom/text "Entities")
     (when (pos? (count selected-entities))
       (dom/span
        (dom/props {:style {:margin-left   "0.5rem"
                            :background    "#3b82f6"
                            :color         "white"
                            :padding       "0.125rem 0.375rem"
                            :border-radius "9999px"
                            :font-size     "0.625rem"
                            :font-weight   "600"}})
        (dom/text (count selected-entities)))))
    (dom/div
     (dom/props {:style filter-list-style})
     ;; New entity button (admin only)
     (when is-admin
       (dom/div
        (dom/props {:style {:padding       "0.375rem 0.75rem"
                            :border-bottom "1px solid #e5e7eb"}})
        (dom/button
         (dom/props {:style (merge (button-style :success :small)
                                   {:width "100%"})})
         (dom/text (t :config/new-entity-btn))
         (let [[tok _] (e/Token (dom/On "click" identity nil))]
           (when tok
             (on-new nil)  ; nil tenant - modal will ask for tenant
             (tok))))))
     ;; Entity list
     (if (empty? all-entities)
       (dom/div
        (dom/props {:style {:padding    "0.5rem 0.75rem"
                            :color      "#9ca3af"
                            :font-size  "0.8rem"
                            :font-style "italic"}})
        (dom/text "No entities"))
       (e/for [entity-id (e/diff-by identity (sort all-entities))]
         (let [entity-name (get entity-names entity-id)
               display-name (if entity-name (str entity-id " (" entity-name ")") entity-id)]
           (dom/label
            (dom/props {:style (merge dropdown-item-style
                                      (when (contains? selected-entities entity-id)
                                        {:background "#eff6ff"}))})
            (dom/input
             (dom/props {:type    "checkbox"
                         :checked (contains? selected-entities entity-id)
                         :style   {:margin-right "0.5rem"}})
             (dom/On "change"
                     (fn [_]
                       (swap! !selected-entities
                              (fn [s]
                                (if (contains? s entity-id)
                                  (disj s entity-id)
                                  (conj s entity-id)))))
                     nil))
            (dom/text display-name)))))))))

(e/defn UnifiedFilterPanel [!selected-tenants !selected-environments !selected-entities !path-filter all-tenants all-entities entity-names tenant-names !refresh-counter is-admin]
  "Unified expandable filter panel with all filters in one row.
   Column order: Path Filter | Entities | Tenants | Environments"
  (e/client
   (let [selected-tenants       (e/watch !selected-tenants)
         selected-environments  (e/watch !selected-environments)
         selected-entities      (e/watch !selected-entities)
         path-filter            (e/watch !path-filter)
         all-environments       ["prod" "staging" "test" "dev"]

         !expanded              (atom false)
         expanded               (e/watch !expanded)

         ;; Modal state
         !show-new-entity-modal (atom nil)
         !show-duplicate-modal  (atom nil)
         show-new-entity-modal  (e/watch !show-new-entity-modal)
         show-duplicate-modal   (e/watch !show-duplicate-modal)

         user-id                (e/server (:user/id e/http-request))

         ;; Count total selected filters (selected-entities is now a flat set)
         total-selected         (+ (count selected-tenants)
                                   (count selected-environments)
                                   (count selected-entities)
                                   (if (not (str/blank? path-filter)) 1 0))]

     ;; Modals
     (when show-new-entity-modal
       (NewEntityModal
        show-new-entity-modal
        (e/fn [entity-id properties]
          (e/server (create-entity-handler! show-new-entity-modal entity-id properties user-id))
          (reset! !show-new-entity-modal nil)
          (swap! !refresh-counter inc))
        !show-new-entity-modal))

     (when show-duplicate-modal
       (let [[tenant source-id] show-duplicate-modal]
         (DuplicateEntityModal
          tenant source-id
          (e/fn [source-entity-id new-entity-id]
            (e/server (duplicate-entity-handler! tenant source-entity-id new-entity-id user-id))
            (reset! !show-duplicate-modal nil)
            (swap! !refresh-counter inc))
          !show-duplicate-modal)))

     (dom/div
      (dom/props {:style filter-panel-style})

      ;; Header row (always visible)
      (dom/div
       (dom/props {:style filter-header-style})
       (dom/div
        (dom/props {:style {:display     "flex"
                            :align-items "center"
                            :gap         "0.5rem"}})
        (dom/span
         (dom/props {:style {:font-weight "600"
                             :font-size   "0.875rem"
                             :color       "#374151"}})
         (dom/text "Filters"))
        (when (pos? total-selected)
          (dom/span
           (dom/props {:style {:background    "#3b82f6"
                               :color         "white"
                               :padding       "0.125rem 0.5rem"
                               :border-radius "9999px"
                               :font-size     "0.75rem"
                               :font-weight   "600"}})
           (dom/text (str total-selected " active")))))
       (dom/button
        (dom/props {:style {:background  "transparent"
                            :border      "none"
                            :cursor      "pointer"
                            :font-size   "0.875rem"
                            :color       "#6b7280"
                            :display     "flex"
                            :align-items "center"
                            :gap         "0.25rem"}})
        (dom/text (if expanded "Collapse" "Expand"))
        (dom/span
         (dom/props {:style {:font-size "0.7rem"}})
         (dom/text (if expanded "▲" "▼")))
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok
            (swap! !expanded not)
            (tok)))))

      ;; Expanded content
      (when expanded
        (dom/div
         (dom/props {:style filter-content-style})

         ;; Path filter (text input with debouncing)
         (dom/div
          (dom/props {:style filter-column-style})
          (dom/span
           (dom/props {:style filter-label-style})
           (dom/text "Path Filter"))
          (let [!local-value    (atom path-filter)
                !debounce-timer (atom nil)
                local-value     (e/watch !local-value)]
            (dom/input
             (dom/props {:type        "text"
                         :placeholder "Filter by path..."
                         :value       local-value
                         :style       {:width         "100%"
                                       :padding       "0.5rem"
                                       :border        "1px solid #e5e7eb"
                                       :border-radius "4px"
                                       :font-size     "0.8rem"
                                       :font-family   "monospace"}})
             (dom/On "input" (fn [e]
                               (let [v (-> e .-target .-value)]
                                 ;; Update local value immediately for responsive UI
                                 (reset! !local-value v)
                                 ;; Clear existing timer
                                 (when-some [timer @!debounce-timer]
                                   (js/clearTimeout timer))
                                 ;; Set new debounced update (300ms delay)
                                 (reset! !debounce-timer
                                         (js/setTimeout
                                          #(reset! !path-filter v)
                                          300))))
                     nil))))

         ;; Entities filter (all entities across all tenants)
         (AllEntitiesFilterColumn
          all-entities
          entity-names
          selected-entities
          !selected-entities
          (e/fn [_t] (reset! !show-new-entity-modal (first all-tenants)))  ; Default to first tenant for new entity
          is-admin)

         ;; Tenants filter (with display names)
         (FilterColumnWithNames "Tenants" all-tenants tenant-names selected-tenants !selected-tenants)

         ;; Environments filter
         (FilterColumn "Environments" all-environments selected-environments !selected-environments)))))))

;; Keep CompactDimensionSelector as an alias for backwards compatibility
(e/defn CompactDimensionSelector [!selected-tenants !selected-environments !selected-entities all-tenants entities-by-tenant entity-names !refresh-counter is-admin]
  "Compact horizontal toolbar with dropdown multi-selects for dimension filtering."
  (e/client
   (let [selected-tenants      (e/watch !selected-tenants)
         selected-environments (e/watch !selected-environments)
         selected-entities     (e/watch !selected-entities)
         all-environments      ["prod" "staging" "test" "dev"]

         ;; Modal state
         !show-new-entity-modal  (atom nil)
         !show-duplicate-modal   (atom nil)
         show-new-entity-modal   (e/watch !show-new-entity-modal)
         show-duplicate-modal    (e/watch !show-duplicate-modal)

         user-id (e/server (:user/id e/http-request))]

     ;; Modals
     (when show-new-entity-modal
       (NewEntityModal
        show-new-entity-modal
        (e/fn [entity-id properties]
          (e/server (create-entity-handler! show-new-entity-modal entity-id properties user-id))
          (reset! !show-new-entity-modal nil)
          (swap! !refresh-counter inc))
        !show-new-entity-modal))

     (when show-duplicate-modal
       (let [[tenant source-id] show-duplicate-modal]
         (DuplicateEntityModal
          tenant source-id
          (e/fn [source-entity-id new-entity-id]
            (e/server (duplicate-entity-handler! tenant source-entity-id new-entity-id user-id))
            (reset! !show-duplicate-modal nil)
            (swap! !refresh-counter inc))
          !show-duplicate-modal)))

     (dom/div
      (dom/props {:style {:display       "flex"
                          :gap           "0.75rem"
                          :margin-bottom "0.75rem"
                          :padding       "0.5rem 0.75rem"
                          :background    "#f9fafb"
                          :border-radius "6px"
                          :align-items   "center"
                          :flex-wrap     "wrap"}})

      ;; Tenants dropdown
      (DropdownMultiSelect "Tenants" all-tenants selected-tenants !selected-tenants)

      ;; Environments dropdown
      (DropdownMultiSelect "Environments" all-environments selected-environments !selected-environments)

      ;; Entity dropdowns (one per selected tenant)
      (e/for [tenant (e/diff-by identity (sort selected-tenants))]
        (let [tenant-entities (get entities-by-tenant tenant [])]
          (EntityDropdownMultiSelect
           tenant
           tenant-entities
           entity-names
           selected-entities
           !selected-entities
           (e/fn [t] (reset! !show-new-entity-modal t))
           (e/fn [t eid] (reset! !show-duplicate-modal [t eid]))
           (e/fn [t eid]
             (e/server (soft-delete-entity-handler! t eid user-id))
             (swap! !refresh-counter inc))
           is-admin)))))))

(e/defn MultiSelectDimensionSelector [!selected-tenants !selected-environments !selected-entities all-tenants entities-by-tenant !refresh-counter is-admin]
  (e/client
   (let [selected-tenants      (e/watch !selected-tenants)
         selected-environments (e/watch !selected-environments)
         selected-entities     (e/watch !selected-entities)
         all-environments      ["prod" "staging" "test" "dev"]

         ;; Modal state for entity management
         !show-new-entity-modal  (atom nil)     ; tenant for new entity
         !show-duplicate-modal   (atom nil)     ; [tenant source-entity-id]
         show-new-entity-modal   (e/watch !show-new-entity-modal)
         show-duplicate-modal    (e/watch !show-duplicate-modal)

         user-id (e/server (:user/id e/http-request))]

     ;; New Entity Modal
     (when show-new-entity-modal
       (NewEntityModal
        show-new-entity-modal
        (e/fn [entity-id properties]
          (e/server (create-entity-handler! show-new-entity-modal entity-id properties user-id))
          (reset! !show-new-entity-modal nil)
          (swap! !refresh-counter inc))
        !show-new-entity-modal))

     ;; Duplicate Entity Modal
     (when show-duplicate-modal
       (let [[tenant source-id] show-duplicate-modal]
         (DuplicateEntityModal
          tenant source-id
          (e/fn [source-entity-id new-entity-id]
            (e/server (duplicate-entity-handler! tenant source-entity-id new-entity-id user-id))
            (reset! !show-duplicate-modal nil)
            (swap! !refresh-counter inc))
          !show-duplicate-modal)))

     (dom/div
      (dom/props {:style {:display       "flex"
                          :gap           "2rem"
                          :margin-bottom "1rem"
                          :padding       "1rem"
                          :background    "#f9fafb"
                          :border-radius "8px"
                          :flex-wrap     "wrap"}})

      ;; Tenants multi-select
      (dom/div
       (dom/props {:style {:min-width "120px"}})
       (dom/label
        (dom/props {:style {:font-weight   "600"
                            :color         "#374151"
                            :display       "block"
                            :margin-bottom "0.5rem"
                            :font-size     "0.875rem"}})
        (dom/text (t :config/tenants)))
       (e/for [tenant (e/diff-by identity all-tenants)]
         (CheckboxItem tenant selected-tenants !selected-tenants)))

      ;; Environments multi-select
      (dom/div
       (dom/props {:style {:min-width "120px"}})
       (dom/label
        (dom/props {:style {:font-weight   "600"
                            :color         "#374151"
                            :display       "block"
                            :margin-bottom "0.5rem"
                            :font-size     "0.875rem"}})
        (dom/text (t :config/environments)))
       (e/for [env (e/diff-by identity all-environments)]
         (CheckboxItem env selected-environments !selected-environments)))))))

(e/defn EditableInheritanceCell [value-entity col-key path def-entity is-effective On-update]
  "Editable cell for inheritance table - clicking opens edit modal."
  (e/client
   (let [has-value    (some? value-entity)
         raw-value    (:config/value value-entity)
         value-length (:value-length value-entity) ; Length of decrypted value for secrets
         encrypted?   (:config-def/encrypted? def-entity)
         can-read     (:can-read def-entity)
         can-write    (:can-write def-entity)
         value-type   (or (:config-def/value-type def-entity) :string)

         !show-modal (atom false)
         show-modal  (e/watch !show-modal)

         user-id    (e/server (:user/id e/http-request))
         user-email (e/server (:user/email e/http-request))]

     ;; Show appropriate modal when open
     (when show-modal
       (if encrypted?
         ;; Secret edit modal for encrypted values
         (SecretEditModal
          path value-length
          ;; on-save callback
          (e/fn [new-value]
            (e/server (set-inheritance-value! col-key path new-value value-type user-id user-email))
            (when On-update
              (On-update))
            (reset! !show-modal false))
          ;; on-reset callback
          (e/fn []
            (e/server (delete-inheritance-value! col-key path user-id))
            (when On-update
              (On-update))
            (reset! !show-modal false))
          has-value
          !show-modal)
         ;; Regular edit modal for non-encrypted values
         (MultilineEditModal
          path value-type (or raw-value "")
          ;; on-save callback
          (e/fn [new-value]
            (e/server (set-inheritance-value! col-key path new-value value-type user-id user-email))
            (when On-update
              (On-update))
            (reset! !show-modal false))
          ;; on-reset callback
          (e/fn []
            (e/server (delete-inheritance-value! col-key path user-id))
            (when On-update
              (On-update))
            (reset! !show-modal false))
          has-value
          !show-modal)))

     (dom/td
      (dom/props {:style (merge inheritance-cell-style
                                (when is-effective effective-cell-style)
                                (when-not has-value {:color "#d1d5db"})
                                (when can-write
                                  {:cursor "pointer"}))
                  :title (when (and has-value can-read (not encrypted?))
                           (str raw-value))})
      (cond
        ;; No read permission
        (not can-read)
        (dom/text "-")

        ;; Encrypted values - show masked with edit capability
        encrypted?
        (dom/div
         (dom/props {:style {:display     "flex"
                             :align-items "center"
                             :gap         "0.25rem"
                             :min-height  "1.25rem"}})
         (dom/span
          (dom/props {:style {:flex "1"}})
          (dom/text (if has-value (mask-secret raw-value) "-"))
          ;; Click on cell content to edit secret
          (when can-write
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (reset! !show-modal true)
                (tok)))))
         (when can-write
           (dom/button
            (dom/props {:style {:opacity    "0.3"
                                :padding    "0"
                                :background "none"
                                :border     "none"
                                :cursor     "pointer"
                                :font-size  "0.6rem"
                                :color      "#3b82f6"}
                        :title "Edit secret"})
            (dom/text "edit")
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (reset! !show-modal true)
                (tok))))))

        ;; Display mode - clicking opens modal
        :else
        (dom/div
         (dom/props {:style {:display     "flex"
                             :align-items "center"
                             :gap         "0.25rem"
                             :min-height  "1.25rem"}})
         (dom/span
          (dom/props {:style {:flex "1"}})
          (dom/text (if has-value (truncate-value raw-value 12) "-"))
          ;; Click on cell content to edit
          (when can-write
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (reset! !show-modal true)
                (tok)))))
         (when can-write
           (dom/button
            (dom/props {:style {:opacity    "0.3"
                                :padding    "0"
                                :background "none"
                                :border     "none"
                                :cursor     "pointer"
                                :font-size  "0.6rem"
                                :color      "#3b82f6"}
                        :title "Edit value"})
            (dom/text "edit")
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (reset! !show-modal true)
                (tok)))))))))))

;; Legacy read-only version for backward compatibility
(e/defn InheritanceCell [value-entity is-effective encrypted? can-read]
  (e/client
   (let [has-value (some? value-entity)
         raw-value (:config/value value-entity)]
     (dom/td
      (dom/props {:style (merge inheritance-cell-style
                                (when is-effective effective-cell-style)
                                (when-not has-value {:color "#d1d5db"}))
                  :title (when (and has-value can-read (not encrypted?))
                           (str raw-value))})
      (cond
        (not can-read)
        (dom/text "-")

        (not has-value)
        (dom/text "-")

        encrypted?
        (dom/text (mask-secret raw-value))

        :else
        (dom/text (truncate-value raw-value 15)))))))

(e/defn InheritancePathRow [def-entity columns path-values on-update]
  (e/client
   (let [path           (:config-def/path def-entity)
         encrypted?     (:config-def/encrypted? def-entity)
         my-path-values (get path-values path {})
         effective-key  (find-effective-column-key my-path-values columns)
         row-bg         (if encrypted? "#fefce8" "white")]
     (dom/tr
      (dom/props {:style {:background row-bg}})
      ;; Path cell - sticky left column
      (dom/td
       (dom/props {:style (merge td-style
                                 {:font-family   "monospace"
                                  :font-size     "0.8rem"
                                  :padding-left  "1.5rem"
                                  :max-width     "300px"
                                  :overflow      "hidden"
                                  :text-overflow "ellipsis"
                                  :white-space   "nowrap"
                                  :position      "sticky"
                                  :left          "0"
                                  :z-index       "10"
                                  :background    row-bg})
                   :title path})
       (dom/text (last (str/split path #"\." 2))))
      ;; Value cells for each column - now editable
      (e/for [col (e/diff-by :key columns)]
        (let [col-key      (:key col)
              value-entity (get my-path-values col-key)
              is-effective (= col-key effective-key)]
          (EditableInheritanceCell value-entity col-key path def-entity is-effective on-update)))))))

(e/defn PathGroupHeader [group-name definitions columns is-expanded !collapsed-groups]
  (e/client
   (dom/tr
    (dom/props {:style {:background "#f3f4f6"
                        :cursor     "pointer"}})
    ;; Group name cell - sticky left column
    (dom/td
     (dom/props {:style {:padding       "0.5rem 0.75rem"
                         :font-weight   "600"
                         :border-bottom "1px solid #e5e7eb"
                         :position      "sticky"
                         :left          "0"
                         :z-index       "10"
                         :background    "#f3f4f6"}})
     (dom/span
      (dom/props {:style {:margin-right "0.5rem"
                          :font-size    "0.75rem"}})
      (dom/text (if is-expanded "▼" "▶")))
     (dom/text (str group-name ".*"))
     (dom/span
      (dom/props {:style {:margin-left "0.5rem"
                          :font-size   "0.75rem"
                          :color       "#6b7280"
                          :font-weight "normal"}})
      (dom/text (str "(" (count definitions) ")")))
     (let [[tok _err] (e/Token (dom/On "click" identity nil))]
       (when tok
         ;; Toggle: if expanded (not in collapsed-groups), add to collapse it
         ;; If collapsed (in collapsed-groups), remove to expand it
         (swap! !collapsed-groups
                (fn [s] (if (contains? s group-name)
                          (disj s group-name)
                          (conj s group-name))))
         (tok))))
    ;; Empty cells for columns
    (e/for [col (e/diff-by :key columns)]
      (dom/td
       (dom/props {:style {:border-bottom "1px solid #e5e7eb"
                           :background    "#f3f4f6"}}))))))

(e/defn PathGroup [group-name definitions columns path-values collapsed-groups !collapsed-groups on-update]
  (e/client
   (let [is-expanded (not (contains? collapsed-groups group-name))
         sorted-defs (sort-by :config-def/path definitions)]
     (dom/tbody
      ;; Group header
      (PathGroupHeader group-name definitions columns is-expanded !collapsed-groups)
      ;; Individual rows (when expanded)
      (when is-expanded
        (e/for [def-entity (e/diff-by :config-def/path sorted-defs)]
          (InheritancePathRow def-entity columns path-values on-update)))))))

(e/defn InheritanceTable [grouped-defs columns path-values collapsed-groups !collapsed-groups on-update]
  (e/client
   (dom/div
    (dom/props {:style {:overflow   "auto"
                        :max-width  "100%"
                        :max-height "70vh"
                        :position   "relative"}})
    (dom/table
     (dom/props {:style (merge table-style {:min-width "800px"
                                            :border-collapse "separate"
                                            :border-spacing "0"})})
     ;; Header - sticky at top
     (dom/thead
      (dom/props {:style {:position "sticky"
                          :top      "0"
                          :z-index  "20"}})
      (dom/tr
       ;; Path header - sticky both top and left (corner cell)
       (dom/th
        (dom/props {:style (merge th-style {:min-width  "200px"
                                            :position   "sticky"
                                            :left       "0"
                                            :top        "0"
                                            :z-index    "30"
                                            :background "#f9fafb"})})
        (dom/text "Path"))
       (e/for [col (e/diff-by :key columns)]
         (dom/th
          (dom/props {:style (merge th-style
                                    {:min-width   "80px"
                                     :max-width   "120px"
                                     :font-size   "0.75rem"
                                     :writing-mode "horizontal-tb"
                                     :text-align  "center"
                                     :background  "#f9fafb"})
                      :title (:label col)})
          (dom/text (:label col))))))
     ;; Body - grouped paths
     (e/for [[group-name defs] (e/diff-by first (vec grouped-defs))]
       (PathGroup group-name defs columns path-values collapsed-groups !collapsed-groups on-update))))))

;; =============================================================================
;; Main Config Component
;; =============================================================================

(e/defn ConfigManagement []
  (e/client
   (let [;; URL-routed state for category
         [selected-category set-category!] (routing/UseRoutedFilter :config-category 3)

         ;; Inheritance view state
         !selected-tenants (atom #{})
         !selected-environments (atom #{})
         !selected-entities (atom #{})  ; Flat set of entity IDs (multi-dimensional model)
         !collapsed-groups (atom #{})
         !path-filter (atom "")

         ;; Shared state
         !refresh-counter (atom 0)

         selected-tenants (e/watch !selected-tenants)
         selected-environments (e/watch !selected-environments)
         selected-entities (e/watch !selected-entities)
         collapsed-groups (e/watch !collapsed-groups)
         path-filter (e/watch !path-filter)
         refresh-counter (e/watch !refresh-counter)

         ;; Get user info from ring request
         user-id (e/server (:user/id e/http-request))]

     (dom/div
      (dom/props {:style {:padding "1rem" :max-width "100%"}})

      ;; Header
      (ks/Heading {:level 2} (e/fn [] (dom/text (t :config/heading))))

      ;; Inheritance view
      (let [table-data (e/server
                        (get-inheritance-table-data-ui
                         selected-tenants
                         selected-environments
                         selected-entities
                         user-id
                         refresh-counter))
            definitions (or (:definitions table-data) [])
            path-values (or (:path-values table-data) {})
            categories (sort (filter some? (:categories table-data)))
            all-tenants (:all-tenants table-data)
            tenant-names (or (:tenant-names table-data) {})
            entities-by-tenant (:entities-by-tenant table-data)
            ;; All entities from database (multi-dimensional model - entities are independent)
            all-entities (or (:all-entities table-data) [])
            entity-names (:entity-names table-data)
            is-admin (:is-admin table-data)
            columns (generate-columns selected-tenants selected-environments selected-entities)
            ;; Filter by category first
            category-filtered (if (= selected-category "all")
                                definitions
                                (filter #(= selected-category (some-> (:config-def/category %) name)) definitions))
            ;; Then filter by path (case-insensitive substring match)
            filtered-defs (if (str/blank? path-filter)
                            category-filtered
                            (let [lower-filter (str/lower-case path-filter)]
                              (filter #(str/includes? (str/lower-case (:config-def/path %)) lower-filter)
                                      category-filtered)))
            grouped-defs (group-paths-by-segment filtered-defs)]
        (dom/div
         ;; Operations panel
         (OperationsPanel all-tenants !refresh-counter is-admin user-id)

         ;; Unified filter panel with all filters in one expandable row
         (UnifiedFilterPanel !selected-tenants !selected-environments !selected-entities !path-filter all-tenants all-entities entity-names tenant-names !refresh-counter is-admin)

         ;; Category tabs with inline stats
        ;;  (RoutedCategoryTabsWithStats categories selected-category set-category! (count filtered-defs) (count grouped-defs) (count columns))

         ;; Inheritance table
         (ks/Card {}
                  (e/fn []
                    (ks/CardBlock {}
                                  (e/fn []
                                    (if (empty? columns)
                                      (dom/div
                                       (dom/props {:style {:padding "2rem"
                                                           :text-align "center"
                                                           :color "#6b7280"}})
                                       (dom/text (t :config/select-dims)))
                                      (InheritanceTable grouped-defs columns path-values collapsed-groups !collapsed-groups
                                                        (e/fn [] (swap! !refresh-counter inc))))))))

         (InheritanceViewLegend)))))))

;; =============================================================================
;; Main Tabbed Config UI
;; =============================================================================

(def tab-style
  {:padding "0.75rem 1.5rem"
   :border "none"
   :background "transparent"
   :cursor "pointer"
   :font-size "0.875rem"
   :color "#6b7280"
   :border-bottom "2px solid transparent"})

(def tab-active-style
  (merge tab-style
         {:color "#3b82f6"
          :font-weight "600"
          :border-bottom "2px solid #3b82f6"}))

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
   (let [[active-tab set-active-tab!] (routing/UseRoutedTab :config 1)]
     (dom/div
      ;; Tab bar
      (dom/div
       (dom/props {:style {:border-bottom "1px solid #e5e7eb"
                           :margin-bottom "1rem"
                           :display "flex"}})
       (RoutedConfigTabButton (t :config/tab-config) 0 active-tab set-active-tab!)
       (RoutedConfigTabButton (t :config/tab-audit) 1 active-tab set-active-tab!)
       (RoutedConfigTabButton (t :config/tab-permissions) 2 active-tab set-active-tab!)
       (RoutedConfigTabButton (t :nav/api-keys) 3 active-tab set-active-tab!)
       (RoutedConfigTabButton "Pipelines" 4 active-tab set-active-tab!)
       (RoutedConfigTabButton "Skills" 5 active-tab set-active-tab!))

      ;; Tab content
      (case active-tab
        0 (ConfigManagement)
        1 (AuditLog)
        2 (Permissions)
        3 (APIKeys)
        4 (Pipelines)
        5 (SkillsUI)
        (ConfigManagement))))))

(e/defn Config []
  (ConfigTabs))
