(ns digdir.config.ui.api-keys
  "API key management UI components."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            [clojure.set :as set]
            #?(:clj [digdir.data.db :as db])
            #?(:clj [digdir.agents.db :as agents-db])
            #?(:clj [digdir.config.api-keys :as api-keys])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.config.structure :as structure])))

#?(:clj
   (defn build-dataset-name-index
     "Build a map of canonical tenant/dataset-config-key IDs to display names."
     [db tenants]
     (reduce (fn [acc tenant]
               (let [dataset-records (->> (config-db/list-config-nodes db tenant :dataset)
                                          (filter :config.node/enabled?)
                                          (keep (fn [node]
                                                  (when-let [{:keys [kind dataset-id]}
                                                             (config-db/parse-dataset-node-id (:config.node/id node))]
                                                    (when (= :base kind)
                                                      (config-db/get-dataset-record db dataset-id)))))
                                          (reduce (fn [records dataset-record]
                                                    (assoc records (:dataset/id dataset-record) dataset-record))
                                                  {})
                                          vals)]
                 (merge acc
                        (into {}
                              (map (fn [dataset-record]
                                     [(str tenant ":" (:dataset/id dataset-record))
                                      (or (:dataset/name dataset-record)
                                          (:dataset/id dataset-record))]))
                              dataset-records))))
             {}
             tenants)))

#?(:clj
   (defn build-allowed-config-key-options
     "Build selectable tenant/root/tenant-config-key options for allowed config key editing."
     [db]
     (->> (for [tenant (config-db/list-tenants db)
                root structure/config-roots-ordered
                node (config-db/list-config-nodes db tenant root)
                :when (and (not (:config.node/system-managed? node))
                           (not (str/blank? (:config.node/tenant-config-key node))))]
            {:tenant tenant
             :root root
             :node-id (:config.node/id node)
             :tenant-config-key (:config.node/tenant-config-key node)
             :label (:config.node/label node)})
          (sort-by (juxt :tenant :root :tenant-config-key))
          vec)))

;; Helper function to format timestamps
#?(:clj
   (defn format-timestamp [epoch-ms]
     (when epoch-ms
       (let [inst (java.time.Instant/ofEpochMilli epoch-ms)
             formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
             zoned (.atZone inst (java.time.ZoneId/of "Europe/Oslo"))]
         (.format formatter zoned)))))

#?(:clj
   (defn days-until-expiry [expires-at]
     (when expires-at
       (let [now (System/currentTimeMillis)
             diff-ms (- expires-at now)]
         (if (neg? diff-ms)
           :expired
           (Math/ceil (/ diff-ms 1000 60 60 24)))))))

(defn mask-api-key
  "Masks an API key showing first 4 and last 3 characters with x's in between.
   E.g., 'rag_abc123xyz789def' becomes 'rag_a-xxxxxxxxx-def'"
  [api-key]
  (when api-key
    (let [len (count api-key)]
      (if (> len 7)
        (str (subs api-key 0 4) "-" (apply str (repeat (- len 7) "x")) "-" (subs api-key (- len 3)))
        api-key))))

(defn all-dataset-option-ids
  "Normalize and sort canonical dataset option IDs."
  [dataset-ids]
  (->> dataset-ids
       distinct
       sort
       vec))

(defn filter-dataset-ids-by-tenants
  "Filter explicit dataset IDs down to the selected tenant set."
  [dataset-ids tenant-ids]
  (let [tenant-id-set (set tenant-ids)]
    (if (empty? tenant-id-set)
      []
      (->> dataset-ids
           (filter (fn [dataset-id]
                     (contains? tenant-id-set
                                (first (str/split dataset-id #":" 3)))))
           vec))))

(defn filter-allowed-config-key-options-by-tenants
  "Filter allowed-config-key options down to the selected tenant set."
  [config-node-options tenant-ids]
  (let [tenant-id-set (set tenant-ids)]
    (if (empty? tenant-id-set)
      []
      (->> config-node-options
           (filter #(contains? tenant-id-set (:tenant %)))
           vec))))

(defn dataset-scope->dataset-id
  "Convert a canonical dataset scope to a stable dataset ID string."
  [{:keys [tenant dataset-config-key tenant-config-key]}]
  (when (and (not (str/blank? tenant))
             (not (str/blank? (or dataset-config-key tenant-config-key))))
    (str tenant ":" (or dataset-config-key tenant-config-key))))

(defn dataset-label
  "Build a stable display label for a dataset ID string."
  [dataset-id dataset-names]
  (let [[tenant dataset-config-key] (str/split dataset-id #":" 2)
        display-name (get dataset-names dataset-id)]
    (if (and display-name (not= display-name dataset-config-key))
      (str display-name " (" tenant "/" dataset-config-key ")")
      (str tenant "/" (or display-name dataset-config-key)))))

(defn dataset-id->dataset-scope
  "Convert a stable dataset ID string to a canonical dataset scope."
  [dataset-id]
  (let [[tenant dataset-config-key] (str/split dataset-id #":" 2)]
    (when (and (not (str/blank? tenant))
               (not (str/blank? dataset-config-key)))
      {:tenant tenant
       :dataset-config-key dataset-config-key})))

(defn agent-label
  "Build a stable display label for an agent ID."
  [agent-id agent-names]
  (let [display-name (get agent-names agent-id)]
    (if (and display-name (not= display-name agent-id))
      (str display-name " (" agent-id ")")
      (or display-name agent-id))))

(defn allowed-config-key-key
  "Build a stable selection key for an allowed config key."
  [{:keys [tenant root tenant-config-key]}]
  (when (and (not (str/blank? tenant))
             root
             (not (str/blank? tenant-config-key)))
    (str tenant "|" (name root) "|" tenant-config-key)))

(defn dataset-ids->tenants
  "Project selected dataset IDs onto the unique tenant set."
  [dataset-ids]
  (->> dataset-ids
       (map #(first (str/split % #":" 2)))
       (remove str/blank?)
       distinct
       sort
       vec))

(defn allowed-config-key-option-label
  "Build a stable display label for a config-node allowed config key option."
  [{:keys [tenant root tenant-config-key node-id label]}]
  (let [node-display (if (and (not (str/blank? label))
                              (not= label tenant-config-key))
                       (str tenant-config-key " - " label)
                       tenant-config-key)]
    (str tenant
         " / "
         (name root)
         " / "
         node-display
         (when-not (str/blank? node-id)
           (str " (" node-id ")")))))

(defn default-allowed-config-key-keys
  "Build default allowed config key selections for selected tenants using the canonical default tenant-config-key."
  [config-node-options tenant-ids]
  (->> config-node-options
       (filter #(and (contains? (set tenant-ids) (:tenant %))
                     (= "default" (:tenant-config-key %))))
       (map allowed-config-key-key)
       (remove nil?)
       distinct
       sort
       vec))

(defn allowed-config-key-options-by-key
  "Index config-node options by their stable selection key."
  [config-node-options]
  (into {}
        (keep (fn [option]
                (when-let [k (allowed-config-key-key option)]
                  [k option])))
        config-node-options))

(defn selected-allowed-config-key-maps
  "Materialize selected allowed-config-key maps from option keys."
  [config-node-options selected-keys]
  (let [options-by-key (allowed-config-key-options-by-key config-node-options)]
    (->> selected-keys
         (keep #(get options-by-key %))
         (mapv (fn [{:keys [tenant root node-id tenant-config-key]}]
                 (cond-> {:tenant tenant
                          :root root
                          :tenant-config-key tenant-config-key}
                   (not (str/blank? node-id))
                   (assoc :node-id node-id)))))))

(e/defn ScopeTag [scope]
  (let [scope-colors {:query {:bg "#dbeafe" :text "#1e40af"}
                      :ingest {:bg "#dcfce7" :text "#166534"}
                      :admin {:bg "#fef3c7" :text "#92400e"}}
        colors (get scope-colors scope {:bg "#e5e7eb" :text "#374151"})]
    (dom/span
     (dom/props {:style {:background (:bg colors)
                         :color (:text colors)
                         :padding "0.125rem 0.5rem"
                         :border-radius "9999px"
                         :font-size "0.75rem"
                         :font-weight "500"
                         :margin-right "0.25rem"}})
     (dom/text (name scope)))))

(e/defn ItemTag [text color-scheme]
  "Display a tag for tenant/root/dataset items."
  (let [colors (case color-scheme
                 :tenant {:bg "#e0e7ff" :text "#3730a3"}
                 :tenant-config-key {:bg "#fce7f3" :text "#9d174d"}
                 :dataset {:bg "#d1fae5" :text "#065f46"}
                 :agent {:bg "#f3e8ff" :text "#6b21a8"}
                 {:bg "#e5e7eb" :text "#374151"})]
    (dom/span
     (dom/props {:style {:background (:bg colors)
                         :color (:text colors)
                         :padding "0.125rem 0.5rem"
                         :border-radius "4px"
                         :font-size "0.75rem"
                         :font-weight "500"
                         :margin-right "0.25rem"
                         :margin-bottom "0.125rem"
                         :display "inline-block"}})
     (dom/text text))))

(defn allowed-config-key-tag-label
  "Build a readable label for an API-key allowed config key."
  [{:keys [tenant root tenant-config-key node-id]}]
  (str tenant
       "/"
       (name root)
       "/"
       tenant-config-key
       (when-not (str/blank? node-id)
         (str " (" node-id ")"))))

(e/defn UnrestrictedSummary [what]
  "Render an EMPTY grant list. Empty means UNRESTRICTED — every agent, dataset
   or config node — not \"none\" (#349, PI decision).

   This used to render the word \"None\" in placeholder grey, which is the
   OPPOSITE of the truth: a key with no agent grants lists and calls every
   agent on /api/mcp, and can open a conversation against any agent it names.
   An operator reading the console saw a restriction that does not exist.

   The wording avoids \"None\"/\"Any\"/blank precisely because those read as
   \"the field is empty\" rather than as a statement about access, and the
   colour is deliberately not the grey used for absent values."
  (dom/span
   (dom/props {:style {:color "#b45309" :font-size "0.75rem" :font-weight "600"}})
   (dom/text (str "Unrestricted \u2014 all " what))))

(e/defn AllowedConfigKeyTag [allowed-config-key]
  (ItemTag (allowed-config-key-tag-label allowed-config-key) :tenant))

(e/defn AllowedConfigKeysSummary [allowed-config-keys]
  (if (empty? allowed-config-keys)
    (UnrestrictedSummary "config nodes")
    (dom/div
     (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.125rem"}})
     (e/for [ceiling (e/diff-by allowed-config-key-key
                                (sort-by (juxt :api-key.allowed-config-key/tenant
                                               :api-key.allowed-config-key/root
                                               :api-key.allowed-config-key/tenant-config-key)
                                         allowed-config-keys))]
       (AllowedConfigKeyTag {:tenant (:api-key.allowed-config-key/tenant ceiling)
                             :root (:api-key.allowed-config-key/root ceiling)
                             :tenant-config-key (:api-key.allowed-config-key/tenant-config-key ceiling)
                             :node-id (:api-key.allowed-config-key/node-id ceiling)})))))

(e/defn LabeledMultiSelectDropdown [label items item-label selected-set !selected-set placeholder empty-text]
  "Multi-select dropdown component."
  (e/client
   (let [!open (atom false)
         open (e/watch !open)]
     (dom/div
      (dom/props {:style {:position "relative" :margin-bottom "1rem"}})

      ;; Label
      (dom/label
       (dom/props {:style {:display "block" :font-weight "500" :margin-bottom "0.25rem"}})
       (dom/text label))

      ;; Dropdown trigger
      (dom/div
       (dom/props {:style {:border "1px solid #d1d5db"
                           :border-radius "4px"
                           :padding "0.5rem"
                           :background "white"
                           :cursor "pointer"
                           :min-height "2.5rem"}})
       (dom/On "click" (fn [_] (swap! !open not)) nil)

       (if (empty? selected-set)
         (dom/span
          (dom/props {:style {:color "#9ca3af"}})
          (dom/text placeholder))
         (dom/div
          (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.25rem"}})
          (e/for [item (e/diff-by identity (sort selected-set))]
            (dom/span
             (dom/props {:style {:background "#e5e7eb"
                                 :padding "0.125rem 0.5rem"
                                 :border-radius "4px"
                                 :font-size "0.875rem"}})
             (dom/text (item-label item)))))))

      ;; Dropdown panel
      (when open
        (dom/div
         (dom/props {:style {:position "absolute"
                             :top "100%"
                             :left "0"
                             :right "0"
                             :background "white"
                             :border "1px solid #d1d5db"
                             :border-radius "4px"
                             :box-shadow "0 4px 6px -1px rgba(0,0,0,0.1)"
                             :z-index "100"
                             :max-height "200px"
                             :overflow-y "auto"}})
         (if (empty? items)
           (dom/div
            (dom/props {:style {:padding "0.5rem" :color "#9ca3af" :font-style "italic"}})
            (dom/text empty-text))
           (e/for [item (e/diff-by identity items)]
             (dom/label
              (dom/props {:style {:display "flex"
                                  :align-items "center"
                                  :padding "0.5rem"
                                  :cursor "pointer"
                                  :hover {:background "#f3f4f6"}}})
              (dom/input
               (dom/props {:type "checkbox"
                           :checked (contains? selected-set item)
                           :style {:margin-right "0.5rem"}})
               (dom/On "change" (fn [_]
                                  (swap! !selected-set
                                         (fn [s] (if (contains? s item)
                                                   (disj s item)
                                                   (conj s item))))) nil))
              (dom/text (item-label item)))))))))))

(e/defn APIKeyRow [api-key dataset-names agent-names !editing-key-id]
  (let [key-id (:api-key/id api-key)
        is-revoked (:api-key/revoked api-key)
        expires-at (:api-key/expires-at api-key)
        days-left (e/server (days-until-expiry expires-at))
        is-expired (= days-left :expired)
        dataset-scopes (or (:api-key/dataset-scopes api-key) [])
        dataset-ids (->> dataset-scopes
                         (map dataset-scope->dataset-id)
                         (remove nil?)
                         vec)
        agent-refs (or (:api-key/agent-refs api-key) [])
        allowed-config-keys (or (:api-key/allowed-config-keys api-key) [])]
    (dom/tr
     (dom/props {:style {:opacity (if (or is-revoked is-expired) "0.6" "1")}})

     ;; Name
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (dom/div
       (dom/props {:style {:font-weight "500"}})
       (dom/text (:api-key/name api-key)))
      (dom/div
       (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :font-family "monospace"}})
       (dom/text (str "ID: " key-id)))
      (when-let [prefix (:api-key/prefix api-key)]
        (dom/div
         (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :font-family "monospace"}})
         (dom/text (str prefix "…" (:api-key/last-four api-key))))))

     ;; Scopes
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (e/for [scope (e/diff-by identity (or (:api-key/scopes api-key) [:query]))]
        (ScopeTag scope)))

     ;; Agents
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (if (empty? agent-refs)
        (UnrestrictedSummary "agents")
        (dom/div
         (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.125rem"}})
         (e/for [agent-id (e/diff-by identity agent-refs)]
           (ItemTag (agent-label agent-id agent-names) :agent)))))

     ;; Datasets
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (if (empty? dataset-ids)
        (UnrestrictedSummary "datasets")
        (dom/div
         (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.125rem"}})
         (e/for [dataset-id (e/diff-by identity dataset-ids)]
           (ItemTag (dataset-label dataset-id dataset-names) :dataset)))))

     ;; Allowed config keys
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (AllowedConfigKeysSummary allowed-config-keys))

     ;; Created
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb" :font-size "0.875rem"}})
      (dom/div (dom/text (e/server (format-timestamp (:api-key/created api-key)))))
      (dom/div
       (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
       (dom/text (str "by " (:api-key/created-by api-key)))))

     ;; Last Used & Usage Count
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb" :font-size "0.875rem"}})
      (if-let [last-used (:api-key/last-used api-key)]
        (dom/div
         (dom/div (dom/text (e/server (format-timestamp last-used))))
         (dom/div
          (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
          (dom/text (str (or (:api-key/usage-count api-key) 0) " uses"))))
        (dom/text "Never")))

     ;; Expiration
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb" :font-size "0.875rem"}})
      (cond
        (not expires-at)
        (dom/span
         (dom/props {:style {:color "#6b7280"}})
         (dom/text "Never"))

        is-expired
        (dom/span
         (dom/props {:style {:color "#dc2626" :font-weight "500"}})
         (dom/text "Expired"))

        :else
        (dom/span
         (dom/props {:style {:color (if (< days-left 7) "#d97706" "#059669")}})
         (dom/text (str days-left " days")))))

     ;; Status & Actions
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (if is-revoked
        (dom/span
         (dom/props {:style {:color "#dc2626" :font-size "0.875rem" :font-weight "500"}})
         (dom/text "Revoked"))
        (dom/div
         (dom/props {:style {:display "flex" :gap "0.5rem" :flex-wrap "wrap"}})
         (ks/Button {:data-size "sm"
                     :data-variant "tertiary"}
                    (e/fn []
                      (dom/text "Edit Allowed Config Keys")
                      (let [[t _] (e/Token (dom/On "click" identity nil))]
                        (when t
                          (case (reset! !editing-key-id key-id)
                            (t))))))
         (ks/Button {:data-size "sm"
                     :data-variant "tertiary"
                     :data-color "danger"}
                    (e/fn []
                      (dom/text "Revoke")
                      (let [[t err] (e/Token (dom/On "click" identity nil))]
                        (when t
                          (case (and (e/client (js/confirm "Are you sure you want to revoke this API key? This cannot be undone."))
                                     (e/server (api-keys/revoke-api-key (db/get-conn) key-id)))
                            (t))))))))))))

(e/defn NewAPIKeyModal [!show-modal !new-key-data config-node-options]
  (let [;; Get tenants and dataset data from server
        tenant-ids (e/server
                    (let [conn (config-db/get-conn)
                          db (some-> conn deref)]
                      (if db
                        (->> (config-db/list-tenants db)
                             sort
                             vec)
                        [])))
        all-dataset-ids (e/server
                         (let [conn (config-db/get-conn)
                               db (some-> conn deref)]
                           (if db
                             (let [tenants (config-db/list-tenants db)]
                               (->> (build-dataset-name-index db tenants)
                                    keys
                                    all-dataset-option-ids))
                             [])))
        dataset-names (e/server
                      (let [conn (config-db/get-conn)
                            db (some-> conn deref)]
                        (if db
                          (let [tenants (config-db/list-tenants db)]
                            (build-dataset-name-index db tenants))
                          {})))
        enabled-agents (e/server
                        (let [conn (config-db/get-conn)]
                          (if conn
                            (agents-db/list-enabled-agents @conn)
                            [])))
        agent-ids (mapv :id enabled-agents)
        agent-names (into {} (map (juxt :id :name) enabled-agents))

        ;; Form state
        !name (atom "")
        !selected-tenant-ids (atom (set (when (= 1 (count tenant-ids))
                                          tenant-ids)))
        !selected-dataset-ids (atom #{})
        !selected-agent-ids (atom #{})
        !scopes (atom #{:query})
        !expires-days (atom "")
        !customize-allowed-config-keys? (atom false)
        !selected-allowed-config-key-keys (atom #{})

        ;; Watched values
        name-val (e/watch !name)
        selected-tenant-ids (e/watch !selected-tenant-ids)
        selected-dataset-ids (e/watch !selected-dataset-ids)
        selected-agent-ids (e/watch !selected-agent-ids)
        scopes-val (e/watch !scopes)
        expires-days-val (e/watch !expires-days)
        customize-allowed-config-keys? (e/watch !customize-allowed-config-keys?)
        selected-allowed-config-key-keys (e/watch !selected-allowed-config-key-keys)
        visible-dataset-ids (filter-dataset-ids-by-tenants all-dataset-ids selected-tenant-ids)
        effective-selected-dataset-ids (set/intersection selected-dataset-ids (set visible-dataset-ids))
        filtered-config-node-options (filter-allowed-config-key-options-by-tenants config-node-options selected-tenant-ids)
        default-allowed-config-key-selection-keys (default-allowed-config-key-keys filtered-config-node-options selected-tenant-ids)
        allowed-config-key-options-by-key (allowed-config-key-options-by-key filtered-config-node-options)
        effective-selected-allowed-config-key-keys (set/intersection selected-allowed-config-key-keys
                                                                     (set (keys allowed-config-key-options-by-key)))
        effective-allowed-config-key-keys (if customize-allowed-config-keys?
                                            (vec (sort effective-selected-allowed-config-key-keys))
                                            default-allowed-config-key-selection-keys)
        effective-allowed-config-keys (selected-allowed-config-key-maps filtered-config-node-options effective-allowed-config-key-keys)

        show-new-key-data! (fn [result]
                             (reset! !show-modal false)
                             (reset! !new-key-data result))]

    ;; Modal backdrop
    (dom/div
     (dom/props {:style {:position "fixed"
                         :top "0"
                         :left "0"
                         :right "0"
                         :bottom "0"
                         :background "rgba(0,0,0,0.5)"
                         :display "flex"
                         :align-items "center"
                         :justify-content "center"
                         :z-index "1000"}})
     (dom/On "click" #(reset! !show-modal false) nil)

     ;; Modal content
     (dom/div
      (dom/props {:style {:background "white"
                          :border-radius "8px"
                          :padding "1.5rem"
                          :max-width "600px"
                          :width "100%"
                          :max-height "90vh"
                          :overflow-y "auto"}})
      (dom/On "click" #(.stopPropagation %) nil)

      (ks/Heading {:level 3 :style {:margin-bottom "1rem"}}
                  (e/fn [] (dom/text "Create New API Key")))

      ;; Name field
      (dom/div
       (dom/props {:style {:margin-bottom "1rem"}})
       (dom/label
        (dom/props {:style {:display "block" :font-weight "500" :margin-bottom "0.25rem"}})
        (dom/text "Name *"))
      (dom/input
        (dom/props {:type "text"
                    :placeholder "e.g., Production Integration"
                    :value name-val
                    :style {:width "100%"
                            :padding "0.5rem"
                            :border "1px solid #d1d5db"
                            :border-radius "4px"}})
        (dom/On "input" #(reset! !name (.. % -target -value)) nil)))

      ;; Tenant grants
      (LabeledMultiSelectDropdown
       "Tenants *"
       tenant-ids
       identity
       selected-tenant-ids
       !selected-tenant-ids
       "Select tenants..."
       "No tenants available")

      ;; Agent grants
      (LabeledMultiSelectDropdown
       "Agents (leave empty = all agents)"
       agent-ids
       #(agent-label % agent-names)
       selected-agent-ids
       !selected-agent-ids
       "Select agents..."
       "No agents available")

      ;; Dataset grants
      (LabeledMultiSelectDropdown
       "Datasets *"
       visible-dataset-ids
       #(dataset-label % dataset-names)
       effective-selected-dataset-ids
       !selected-dataset-ids
       "Select datasets..."
       "No datasets available for the selected tenant")

      (dom/div
       (dom/props {:style {:margin-top "-0.5rem"
                           :margin-bottom "1rem"
                           :font-size "0.75rem"
                           :color "#6b7280"}})
       (dom/text "Dataset grants are explicit dataset-scope selections within the selected tenant."))

      ;; Allowed config keys
      (dom/div
       (dom/props {:style {:margin-bottom "1rem"}})
       (dom/label
        (dom/props {:style {:display "block" :font-weight "500" :margin-bottom "0.5rem"}})
        (dom/text "Allowed Config Keys"))
       (dom/div
        (dom/props {:style {:margin-bottom "0.5rem"}})
        (dom/label
         (dom/props {:style {:display "flex" :align-items "center" :gap "0.5rem" :cursor "pointer"}})
         (dom/input
          (dom/props {:type "checkbox"
                      :checked customize-allowed-config-keys?})
          (dom/On "change" (fn [_]
                             (swap! !customize-allowed-config-keys?
                                    (fn [current]
                                      (let [next (not current)]
                                        (when (and next (empty? @!selected-allowed-config-key-keys))
                                          (reset! !selected-allowed-config-key-keys (set default-allowed-config-key-selection-keys)))
                                        next)))) nil))
         (dom/span (dom/text "Customize allowed config keys instead of using tenant defaults"))))
       (if customize-allowed-config-keys?
         (LabeledMultiSelectDropdown
          "Allowed config nodes (leave empty = all)"
          (mapv allowed-config-key-key filtered-config-node-options)
          #(allowed-config-key-option-label (get allowed-config-key-options-by-key %))
          effective-selected-allowed-config-key-keys
          !selected-allowed-config-key-keys
          "Select config nodes..."
          "No config nodes available for the selected tenant")
         (dom/div
          (dom/props {:style {:font-size "0.75rem"
                              :color "#6b7280"
                              :display "grid"
                              :gap "0.25rem"}})
          (dom/text "Default mode grants the canonical 'default' node for every available config root in each selected tenant.")
          (if (empty? effective-allowed-config-key-keys)
            (dom/text "Select dataset grants to derive tenant defaults.")
            (e/for [config-key (e/diff-by identity effective-allowed-config-key-keys)]
              (let [option (get allowed-config-key-options-by-key config-key)]
                (dom/div (dom/text (allowed-config-key-option-label option)))))))))

      ;; Scopes selection
      (dom/div
       (dom/props {:style {:margin-bottom "1rem"}})
       (dom/label
        (dom/props {:style {:display "block" :font-weight "500" :margin-bottom "0.5rem"}})
        (dom/text "Scopes *"))
       (dom/div
        (dom/props {:style {:display "flex" :gap "1rem" :flex-wrap "wrap"}})
        (e/for [[scope desc] (e/diff-by first [[:query "Read/query the RAG API"]
                                                [:ingest "Ingest documents"]
                                                [:admin "Administrative operations"]])]
          (dom/label
           (dom/props {:style {:display "flex" :align-items "center" :gap "0.5rem" :cursor "pointer"}})
           (dom/input
            (dom/props {:type "checkbox"
                        :checked (contains? scopes-val scope)})
            (dom/On "change" #(swap! !scopes (fn [s] (if (contains? s scope)
                                                        (disj s scope)
                                                        (conj s scope)))) nil))
           (dom/span (dom/text (name scope)))
           (dom/span
            (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
            (dom/text (str "(" desc ")")))))))

      ;; Expiration field
      (dom/div
       (dom/props {:style {:margin-bottom "1.5rem"}})
       (dom/label
        (dom/props {:style {:display "block" :font-weight "500" :margin-bottom "0.25rem"}})
        (dom/text "Expires in (days)"))
       (dom/input
        (dom/props {:type "number"
                    :placeholder "Leave empty for no expiration"
                    :value expires-days-val
                    :min "1"
                    :style {:width "100%"
                            :padding "0.5rem"
                            :border "1px solid #d1d5db"
                            :border-radius "4px"}})
        (dom/On "input" #(reset! !expires-days (.. % -target -value)) nil)))

      ;; Buttons
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem" :justify-content "flex-end"}})

       (ks/Button {:data-variant "secondary"}
                  (e/fn []
                    (dom/text "Cancel")
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (case (reset! !show-modal false)
                          (t))))))

       (ks/Button {:data-variant "primary"
                   :disabled (or (str/blank? name-val)
                                 (empty? selected-tenant-ids)
                                 (empty? effective-selected-dataset-ids)
                                 (empty? scopes-val))}
                  (e/fn []
                    (dom/text "Create Key")
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (let [expires-days-num (when (not (str/blank? expires-days-val))
                                                 (parse-long expires-days-val))
                              scopes-vec (vec scopes-val)
                              dataset-ids-vec (vec effective-selected-dataset-ids)
                              agent-refs-vec (vec selected-agent-ids)
                              dataset-scopes (->> dataset-ids-vec
                                                  (map dataset-id->dataset-scope)
                                                (remove nil?)
                                                vec)
                              result (e/server
                                      (let [created-by (or (:user/email e/http-request) "unknown")]
                                        (api-keys/create-api-key! (db/get-conn)
                                                                  name-val
                                                                  created-by
                                                                  {:dataset-scopes dataset-scopes
                                                                   :agent-refs agent-refs-vec
                                                                   :allowed-config-keys effective-allowed-config-keys
                                                                   :scopes scopes-vec
                                                                   :expires-in-days expires-days-num})))]
                          ;; First set the key data, then close the create modal
                          (case (show-new-key-data! result)
                            (t))))))))))))

(e/defn KeyCreatedModal [!new-key-data]
  (let [key-data (e/watch !new-key-data)]
    ;; Modal backdrop
    (dom/div
     (dom/props {:style {:position "fixed"
                         :top "0"
                         :left "0"
                         :right "0"
                         :bottom "0"
                         :background "rgba(0,0,0,0.5)"
                         :display "flex"
                         :align-items "center"
                         :justify-content "center"
                         :z-index "1000"}})

     ;; Modal content
     (dom/div
      (dom/props {:style {:background "white"
                          :border-radius "8px"
                          :padding "1.5rem"
                          :max-width "600px"
                          :width "100%"}})

      (ks/Heading {:level 3 :style {:margin-bottom "0.5rem"}}
                  (e/fn [] (dom/text "API Key Created Successfully")))

      (ks/Alert {:data-color "warning" :style {:margin-bottom "1rem"}}
                (e/fn []
                  (dom/text "Copy this key now. You won't be able to see it again!")))

      ;; Key display
      (dom/div
       (dom/props {:style {:background "#f3f4f6"
                           :padding "1rem"
                           :border-radius "6px"
                           :font-family "monospace"
                           :font-size "0.875rem"
                           :word-break "break-all"
                           :margin-bottom "1rem"}})
       (dom/text (:api-key key-data)))

      ;; Action buttons
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem" :justify-content "flex-end"}})

       ;; Copy button
       (ks/Button {:data-variant "secondary"}
                  (e/fn []
                    (dom/text "Copy to Clipboard")
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (case (e/client
                               (-> (js/navigator.clipboard.writeText (:api-key key-data))
                                   (.then #(js/alert "API key copied to clipboard!"))
                                   (.catch #(js/alert "Failed to copy. Please copy manually."))))
                          (t))))))

       ;; Download button
       (ks/Button {:data-variant "secondary"}
                  (e/fn []
                    (dom/text "Download as JSON")
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (case (e/client
                               #?(:cljs
                                  (let [json-data (js/JSON.stringify (clj->js {:api_key (:api-key key-data)
                                                                               :name (:name key-data)
                                                                               :created_at (js/Date.)}) nil 2)
                                        blob (js/Blob. #js [json-data] #js {:type "application/json"})
                                        url (js/URL.createObjectURL blob)
                                        a (js/document.createElement "a")]
                                    (set! (.-href a) url)
                                    (set! (.-download a) (str "api-key-" (:name key-data) ".json"))
                                    (.click a)
                                    (js/URL.revokeObjectURL url))))
                          (t))))))

       ;; Done button
       (ks/Button {:data-variant "primary"}
                  (e/fn []
                    (dom/text "Done")
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        ;; Clear the modal by resetting the atom
                        (case (reset! !new-key-data nil)
                          (t)))))))))))

(e/defn EditAllowedConfigKeysModal [api-key config-node-options !editing-key-id]
  (let [!selected-allowed-config-key-keys (atom (set (keep allowed-config-key-key (or (:api-key/allowed-config-keys api-key) []))))
        selected-allowed-config-key-keys (e/watch !selected-allowed-config-key-keys)
        allowed-config-key-options-by-key (allowed-config-key-options-by-key config-node-options)
        key-id (:api-key/id api-key)]
    (dom/div
     (dom/props {:style {:position "fixed"
                         :top "0"
                         :left "0"
                         :right "0"
                         :bottom "0"
                         :background "rgba(0,0,0,0.5)"
                         :display "flex"
                         :align-items "center"
                         :justify-content "center"
                         :z-index "1000"}})
     (dom/On "click" #(reset! !editing-key-id nil) nil)

     (dom/div
      (dom/props {:style {:background "white"
                          :border-radius "8px"
                          :padding "1.5rem"
                          :max-width "700px"
                          :width "100%"
                          :max-height "90vh"
                          :overflow-y "auto"}})
      (dom/On "click" #(.stopPropagation %) nil)

      (ks/Heading {:level 3 :style {:margin-bottom "1rem"}}
                  (e/fn [] (dom/text (str "Edit Allowed Config Keys: " (:api-key/name api-key)))))

      (ks/Paragraph {:style {:margin-bottom "1rem" :color "#4b5563"}}
                    (e/fn []
                      (dom/text "Saving allowed config keys rewrites the stored node-ids to match the selected tenant/root/tenant-config-key entries.")))

      (LabeledMultiSelectDropdown
       "Allowed config nodes (leave empty = all)"
       (mapv allowed-config-key-key config-node-options)
       #(allowed-config-key-option-label (get allowed-config-key-options-by-key %))
       selected-allowed-config-key-keys
       !selected-allowed-config-key-keys
       "Select config nodes..."
       "No config nodes available")

      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem" :justify-content "flex-end"}})

       (ks/Button {:data-variant "secondary"}
                  (e/fn []
                    (dom/text "Cancel")
                    (let [[t _] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (case (reset! !editing-key-id nil)
                          (t))))))

       (ks/Button {:data-variant "primary"
                   :disabled (empty? selected-allowed-config-key-keys)}
                  (e/fn []
                    (dom/text "Save Allowed Config Keys")
                    (let [[t _] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (let [selected-allowed-config-keys (selected-allowed-config-key-maps config-node-options selected-allowed-config-key-keys)
                              _ (e/server
                                 (api-keys/replace-api-key-allowed-config-keys!
                                  (db/get-conn)
                                  key-id
                                  selected-allowed-config-keys
                                  {:user-email (or (:user/email e/http-request) "unknown")
                                   :user-id (or (:user/id e/http-request)
                                                (:user/email e/http-request)
                                                "unknown")}))]
                          (case (reset! !editing-key-id nil)
                            (t))))))))))))

(e/defn APIKeys []
  (let [;; Server-side data - watch the db for reactivity
        api-keys-list (e/server
                       (let [conn (db/get-conn)]
                         (vec (sort-by :api-key/created > (api-keys/list-all-api-keys (e/watch conn))))))
        config-node-options (e/server
                             (let [conn (config-db/get-conn)
                                   db (some-> conn deref)]
                               (if db
                                 (build-allowed-config-key-options db)
                                 [])))
        ;; Get dataset names for display
        dataset-names (e/server
                      (let [conn (config-db/get-conn)
                            db (some-> conn deref)]
                        (if db
                          (let [tenants (config-db/list-tenants db)]
                            (build-dataset-name-index db tenants))
                          {})))
        agent-names (e/server
                     (let [conn (config-db/get-conn)]
                       (if conn
                         (into {} (map (juxt :id :name) (agents-db/list-enabled-agents @conn)))
                         {})))
        ;; Client-side UI state atoms
        !show-create-modal (atom false)
        show-create-modal (e/watch !show-create-modal)
        !new-key-data (atom nil)
        new-key-data (e/watch !new-key-data)
        !editing-key-id (atom nil)
        editing-key-id (e/watch !editing-key-id)
        editing-api-key (some #(when (= editing-key-id (:api-key/id %)) %) api-keys-list)
        !show-revoked (atom false)
        show-revoked (e/watch !show-revoked)
        revoked-count (count (filter :api-key/revoked api-keys-list))
        visible-api-keys (if show-revoked
                           api-keys-list
                           (remove :api-key/revoked api-keys-list))]
    (dom/div
     (dom/props {:style {:padding "1rem"
                         :max-width "1600px"}})

     (dom/div
      (dom/props {:style {:display "flex"
                          :justify-content "space-between"
                          :align-items "center"
                          :margin-bottom "1rem"}})

      (ks/Heading {:level 2}
                  (e/fn [] (dom/text "API Keys")))

      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem" :align-items "center"}})

       (when (pos? revoked-count)
         (ks/Button {:data-variant "tertiary"}
                    (e/fn []
                      (dom/text (if show-revoked
                                  "Hide revoked"
                                  (str "Show revoked (" revoked-count ")")))
                      (let [[t err] (e/Token (dom/On "click" identity nil))]
                        (when t
                          (case (swap! !show-revoked not)
                            (t)))))))

       (ks/Button {:data-variant "primary"}
                  (e/fn []
                    (dom/text "Create New Key")
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (case (reset! !show-create-modal true)
                          (t))))))))

     (ks/Card {}
              (e/fn []
                (ks/CardBlock {}
                              (e/fn []
                                (ks/Paragraph {:style {:margin-bottom "1rem"}}
                                              (e/fn [] (dom/text "Manage API keys for accessing the RAG API. Keys are used for authentication and can have different permission scopes.")))

                                (if (empty? visible-api-keys)
                                  (dom/div
                                   (dom/props {:style {:text-align "center"
                                                       :padding "2rem"
                                                       :color "#6b7280"}})
                                   (dom/text (if (pos? revoked-count)
                                              (str "All " revoked-count " key(s) are revoked. Use \"Show revoked\" to view them.")
                                              "No API keys yet. Create one to get started.")))

                                  ;; API Keys table
                                  (dom/div
                                   (dom/props {:style {:overflow-x "auto"}})
                                   (dom/table
                                    (dom/props {:style {:width "100%"
                                                        :border-collapse "collapse"}})
                                    (dom/thead
                                     (dom/tr
                                      (dom/props {:style {:background "#f9fafb"}})
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Name"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Scopes"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Agents"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Datasets"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Allowed Config Keys"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Created"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Last Used"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Expires"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Actions"))))
                                    (dom/tbody
                                     (e/for [api-key (e/diff-by :api-key/id visible-api-keys)]
                                       (APIKeyRow api-key dataset-names agent-names !editing-key-id))))))))))

     ;; Modals
     (when show-create-modal
       (NewAPIKeyModal !show-create-modal !new-key-data config-node-options))

     (when new-key-data
       (KeyCreatedModal !new-key-data))

     (when editing-api-key
       (EditAllowedConfigKeysModal editing-api-key config-node-options !editing-key-id)))))
