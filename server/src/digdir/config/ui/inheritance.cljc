(ns digdir.config.ui.inheritance
  "Interactive inheritance editor for the V2 config model."
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [clojure.string :as str]
            [digdir.config.ui.styles :as styles]
            [digdir.config.ui.common :as common]
            [digdir.i18n :refer [t]]))

;; =============================================================================
;; Styles
;; =============================================================================

(def ^:private inheritance-table-style
  {:display "grid"
   :grid-template-columns "minmax(250px, 1fr) repeat(var(--col-count, 1), minmax(140px, 180px))"
   :border "1px solid #e5e7eb"
   :border-radius "8px"
   :overflow "auto"
   :background "white"
   :font-size "0.875rem"})

(def ^:private inheritance-header-style
  {:display "contents"
   :font-weight "600"
   :color "#374151"
   :background "#f9fafb"})

(def ^:private inheritance-cell-style
  {:padding "0.625rem 0.875rem"
   :border-bottom "1px solid #f3f4f6"
   :border-right "1px solid #f3f4f6"
   :white-space "nowrap"
   :overflow "hidden"
   :text-overflow "ellipsis"})

(def ^:private inheritance-path-cell-style
  (merge inheritance-cell-style
         {:font-family "monospace"
          :background "#fcfcfc"
          :font-weight "500"}))

(def ^:private inheritance-group-header-style
  {:grid-column "1 / -1"
   :background "#f3f4f6"
   :padding "0.375rem 0.875rem"
   :font-size "0.75rem"
   :font-weight "700"
   :color "#4b5563"
   :text-transform "uppercase"
   :letter-spacing "0.025em"
   :border-bottom "1px solid #e5e7eb"})

;; Reused from styles ns
(def ^:private button-style styles/button-style)
(def ^:private input-style styles/input-style)
(def ^:private textarea-style styles/textarea-style)
(def ^:private diagnostics-label-style styles/diagnostics-label-style)

;; =============================================================================
;; Helper Components
;; =============================================================================

(e/defn CategoryTabButton [cat selected !selected-category size]
  (let [selected? (= (name cat) (name selected))
        padding (if (= size :compact) "0.25rem 0.5rem" "0.5rem 1rem")
        font-size (if (= size :compact) "0.8rem" "0.875rem")]
    (dom/button
     (dom/props {:style {:padding       padding
                         :background    (if selected? "#3b82f6" "transparent")
                         :color         (if selected? "white" "#6b7280")
                         :border        "none"
                         :border-radius "4px"
                         :cursor        "pointer"
                         :font-size     font-size
                         :font-weight   "500"}})
     (dom/text (str/capitalize (name cat)))
     (let [[tok _] (e/Token (dom/On "click" identity nil))]
       (when tok (reset! !selected-category cat) (tok))))))

(e/defn CategoryTabsWithStats [categories !selected-category config-count group-count column-count]
  (let [selected (e/watch !selected-category)]
    (dom/div
     (dom/props {:style {:display         "flex"
                         :justify-content "space-between"
                         :align-items     "center"
                         :border-bottom   "1px solid #e5e7eb"
                         :padding-bottom  "0.5rem"}})
     ;; Category tabs
     (dom/div
      (dom/props {:style {:display "flex"
                          :gap     "0.25rem"}})
      (CategoryTabButton :all selected !selected-category :normal)
      (e/for [cat (e/diff-by identity categories)]
        (CategoryTabButton cat selected !selected-category :normal)))

     ;; Stats
     (dom/div
      (dom/props {:style {:font-size "0.8rem"
                          :color     "#6b7280"}})
      (dom/text (str config-count " configs | " group-count " groups | " column-count " cols"))))))

(def ^:private ownership-badge-colors
  {:inherit {:bg "#eef2ff" :fg "#3730a3" :border "#c7d2fe"}
   :fork    {:bg "#f5f5f4" :fg "#57534e" :border "#e7e5e4"}})

(e/defn OwnershipBadge [ownership]
  (let [kind (or ownership :fork)
        {:keys [bg fg border]} (get ownership-badge-colors kind)]
    (dom/span
     (dom/props {:style {:display "inline-block"
                         :padding "1px 6px"
                         :margin-left "0.4rem"
                         :font-size "0.65rem"
                         :font-weight "600"
                         :letter-spacing "0.03em"
                         :text-transform "uppercase"
                         :background bg
                         :color fg
                         :border (str "1px solid " border)
                         :border-radius "9999px"
                         :vertical-align "middle"}
                 :title (case kind
                          :inherit "inherit — tenant values override the live global baseline"
                          "fork — tenant owns its value after registration")})
     (dom/text (name kind)))))

(def ^:private provenance-pill-styles
  {:tenant-fork  {:bg "#f3f4f6" :fg "#374151" :label "tenant"}
   :tenant-inherit {:bg "#fef3c7" :fg "#92400e" :label "override"}
   :pinned       {:bg "#fed7aa" :fg "#9a3412" :label "pinned"}
   :global       {:bg "#dbeafe" :fg "#1e40af" :label "global"}})

(e/defn GlobalVersionPill [version]
  "Compact pill showing the current global-config version."
  (dom/span
   (dom/props {:style {:display "inline-flex"
                       :align-items "center"
                       :gap "0.3rem"
                       :padding "2px 8px"
                       :font-size "0.7rem"
                       :font-weight "600"
                       :background "#eef2ff"
                       :color "#3730a3"
                       :border "1px solid #c7d2fe"
                       :border-radius "9999px"}
               :title "Monotonic global-config version. Bumped on every global-edit."})
   (dom/text (str "Global v" (or version 0)))))

(e/defn ProvenancePill [kind version]
  "Provenance pill, intended to be absolutely-positioned by its container
   (the cell div sets :position \"relative\"). The pill itself sits
   top-right via the absolute positioning wrapper below."
  (when-let [{:keys [bg fg label]} (get provenance-pill-styles kind)]
    (dom/span
     (dom/props {:style {:position "absolute"
                         :top "2px"
                         :right "4px"
                         :padding "0 4px"
                         :font-size "0.6rem"
                         :font-weight "600"
                         :letter-spacing "0.02em"
                         :text-transform "uppercase"
                         :background bg
                         :color fg
                         :border-radius "3px"
                         :line-height "1rem"
                         :pointer-events "none"}
                 :title (case kind
                          :pinned (str "pinned to global v" (or version "?"))
                          :global (str "inherited from global" (when version (str " v" version)))
                          :tenant-inherit "tenant override of an inherit-owned path"
                          :tenant-fork "tenant-owned value"
                          nil)})
     (dom/text label))))

(def ^:private global-tenant-id
  "Must match digdir.config.core/global-tenant. Inlined to keep this cljc
   surface free of a server-only require."
  "__global__")

(defn cell-provenance-kind
  "Compute the provenance pill kind for a cell given the winning info.
   Returns one of :tenant-fork, :tenant-inherit, :pinned, :global, or nil.

   The `__global__` tenant column is a regular tenant column at the data
   layer, but semantically its values ARE the global baseline. Label them
   :global rather than :tenant-inherit so operators don't see 'OVERRIDE'
   on the very row that defines the default."
  [{:keys [column-kind ownership winner? value tenant]}]
  (cond
    ;; Synthetic global column (column-kind :global) OR the __global__ tenant's
    ;; own column — both are the baseline, not an override.
    (and winner? (or (= column-kind :global)
                     (= tenant global-tenant-id))) :global
    (and winner? (= column-kind :tenant)
         (:config.value/pin-of-version value)) :pinned
    (and winner? (= column-kind :tenant) (= ownership :inherit)) :tenant-inherit
    (and winner? (= column-kind :tenant)) :tenant-fork
    :else nil))

(defn display-value-for-type
  "Format a decoded value for display given its config value-type.
   For :string, use the raw string (no surrounding quotes); for other types,
   fall back to pr-str so edn / number / boolean / map render readably."
  [value value-type]
  (cond
    (nil? value) nil
    (= :string value-type) (str value)
    :else (pr-str value)))

(e/defn InheritanceValueCell
  "Render a single value cell. Extended for Phase 2 to accept ownership,
   column-kind, and the raw value entity (to detect :pin-of-version).

   Legacy arity preserved for callers that don't pass ownership metadata."
  ([path node-id value winner? On-edit]
   (InheritanceValueCell path node-id value winner? On-edit {:column-kind :tenant
                                                              :ownership nil
                                                              :value-entity nil
                                                              :version nil
                                                              :value-type nil
                                                              :tenant nil}))
  ([path node-id value winner? On-edit {:keys [column-kind ownership value-entity version value-type tenant]}]
   (let [has-value? (some? value)
         global-col? (= column-kind :global)
         display-text (if has-value? (display-value-for-type value value-type) nil)
         style (cond-> inheritance-cell-style
                 winner? (merge {:background "#dcfce7"
                                 :border "1px solid #22c55e"
                                 :color "#166534"
                                 :z-index "1"})
                 (not has-value?) (merge {:color "#9ca3af"
                                          :text-align "center"})
                 (and global-col? (not winner?)) (merge {:background "#f8fafc"
                                                         :color "#475569"}))
         pin-version (:config.value/pin-of-version value-entity)
         provenance (cell-provenance-kind {:column-kind column-kind
                                           :ownership ownership
                                           :winner? winner?
                                           :value value-entity
                                           :tenant tenant})]
     (dom/div
      (dom/props {:style (merge style {:cursor "pointer"
                                       :position "relative"})
                  :title (str "Node: " node-id
                              "\nPath: " path
                              "\nValue: " (pr-str value)
                              (when pin-version (str "\nPinned against global v" pin-version)))})
      (dom/text (if has-value? (common/truncate-value display-text 40) "-"))
      (when provenance (ProvenancePill provenance (or pin-version version)))
      (let [[tok _] (e/Token (dom/On "click" identity nil))]
        (when tok (On-edit) (tok)))))))

(e/defn LegendContainer [Content]
  (dom/div
   (dom/props {:style {:display       "flex"
                       :align-items   "center"
                       :gap           "1rem"
                       :margin-bottom "1rem"
                       :padding       "0.75rem"
                       :background    "#f9fafb"
                       :border        "1px solid #e5e7eb"
                       :border-radius "4px"
                       :font-size     "0.75rem"
                       :color         "#6b7280"}})
   (dom/span
    (dom/props {:style {:font-weight "600"}})
    (dom/text (t :config/legend)))
   (Content)))

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
      (dom/span (dom/text (t :config/legend-effective)))
      (dom/span
       (dom/props {:style {:font-weight "600"
                           :margin-right "0.25rem"}})
       (dom/text "-"))
      (dom/span (dom/text (t :config/legend-inherit)))))))

(defn- category-name
  [category]
  (some-> category name str/lower-case))

(defn filter-definitions
  [definitions selected-category path-filter]
  (let [selected-category-name (category-name selected-category)
        path-filter-text (some-> (or path-filter "")
                                 str/trim
                                 str/lower-case)]
    (->> definitions
         (filter (fn [definition]
                   (or (= selected-category :all)
                       (= (category-name (:config-def/category definition))
                          selected-category-name))))
         (filter (fn [definition]
                   (or (str/blank? path-filter-text)
                       (str/includes? (str/lower-case (:config-def/path definition))
                                      path-filter-text)
                       (str/includes? (or (category-name (:config-def/category definition)) "")
                                      path-filter-text))))
         vec)))

(defn- node-option-label
  [node]
  (or (:config.node/label node)
      (:config.node/tenant-config-key node)
      (:config.node/id node)))

(defn comparison-node-options
  [tenant-results]
  (let [successful-results (->> tenant-results
                                (filter #(= :success (:status %)))
                                (map :data)
                                vec)
        tenant-count (count successful-results)
        options-by-key (reduce (fn [acc {:keys [tenant nodes]}]
                                 (reduce (fn [inner node]
                                           (let [tenant-config-key (:config.node/tenant-config-key node)]
                                             (if (str/blank? tenant-config-key)
                                               inner
                                               (update inner tenant-config-key
                                                       (fn [entry]
                                                         {:tenant-config-key tenant-config-key
                                                          :label (or (:label entry)
                                                                     (node-option-label node))
                                                          :representative (or (:representative entry) node)
                                                          :tenants (conj (or (:tenants entry) #{}) tenant)})))))
                                         acc
                                         nodes))
                               {}
                               successful-results)]
    (->> options-by-key
         vals
         (filter (fn [{:keys [tenants]}]
                   (or (<= tenant-count 1)
                       (= tenant-count (count tenants)))))
         (sort-by (fn [{:keys [tenant-config-key label]}]
                    [(if (= "default" tenant-config-key) 0 1)
                     (str/lower-case (or label tenant-config-key))
                     tenant-config-key]))
         vec)))

(defn- comparison-categories
  [definitions]
  (->> definitions
       (keep :config-def/category)
       (sort-by category-name)
       distinct
       vec))

(defn merge-definitions
  [tenant-results]
  (->> tenant-results
       (filter #(= :success (:status %)))
       (mapcat (comp :definitions :data))
       (reduce (fn [acc definition]
                 (assoc acc (:config-def/path definition) definition))
               {})
       vals
       (sort-by :config-def/path)
       vec))

(defn selected-comparison-selections
  [selected-node-cells]
  (->> selected-node-cells
       (keep (fn [[tenant root tenant-config-key]]
               (when (and (seq tenant) root (seq tenant-config-key))
                 {:tenant tenant
                  :root root
                  :tenant-config-key tenant-config-key})))
       distinct
       (sort-by (juxt :tenant :root :tenant-config-key))
       vec))

(defn- tenant-columns
  [tenant-results multi-tenant?]
  (->> tenant-results
       (mapcat (fn [{:keys [tenant root data]}]
                 (let [{:keys [resolution values-by-node]} data
                       root-label (name root)
                       chain (reverse (get-in resolution [:chain]))]
                   (map-indexed
                    (fn [idx node]
                      {:column-id (str tenant "::" root-label "::" (:config.node/id node))
                       :column-kind :tenant
                       :tenant tenant
                       :root root
                       :node node
                       :node-id (:config.node/id node)
                       :position idx
                       :values-by-node values-by-node
                       :results (get-in resolution [:results])
                       :header (if multi-tenant?
                                 (str tenant " / " root-label " / " (node-option-label node))
                                 (str root-label " / " (node-option-label node)))})
                    chain))))
       vec))

(defn- global-columns
  "Build one virtual global column per unique root represented in the successful
   results that has a global-layer available. Roots where the __global__ tenant
   has already been selected as an explicit tenant column are skipped — the real
   tenant column already shows the same values."
  [tenant-results]
  (let [roots-already-global-tenant (->> tenant-results
                                         (filter #(= "__global__" (:tenant %)))
                                         (map :root)
                                         set)
        per-root (reduce (fn [acc {:keys [root data tenant]}]
                           (if (and (not= "__global__" tenant)
                                    (not (contains? roots-already-global-tenant root)))
                             (if-let [layer (:global-layer data)]
                               (assoc acc root layer)
                               acc)
                             acc))
                         {}
                         tenant-results)]
    (->> per-root
         (map (fn [[root {:keys [root-node values-by-node]}]]
                {:column-id (str "__global__::" (name root))
                 :column-kind :global
                 :tenant "__global__"
                 :root root
                 :node root-node
                 :node-id (:config.node/id root-node)
                 :position 0
                 :values-by-node values-by-node
                 :results nil
                 :header (str "Global / " (name root))}))
         (sort-by :root)
         vec)))

(defn comparison-columns
  [tenant-results]
  (let [successful-results (->> tenant-results
                                (filter #(= :success (:status %)))
                                vec)
        multi-tenant? (> (count successful-results) 1)]
    (into (tenant-columns successful-results multi-tenant?)
          (global-columns successful-results))))

(defn selected-node-columns
  [tenant-results]
  (let [successful-results (->> tenant-results
                                (filter #(= :success (:status %)))
                                vec)
        multi-tenant? (> (count successful-results) 1)
        tenant-cols (->> successful-results
                         (map-indexed
                          (fn [idx {:keys [tenant root data column-label selection-id]}]
                            (let [selected-node (:selected-node data)
                                  selected-node-id (:config.node/id selected-node)
                                  label (or column-label
                                            (node-option-label selected-node))
                                  root-label (name root)]
                              {:column-id (or selection-id
                                              (str tenant "::" root-label "::" selected-node-id))
                               :column-kind :tenant
                               :tenant tenant
                               :root root
                               :node selected-node
                               :node-id selected-node-id
                               :position idx
                               :values-by-node (:values-by-node data)
                               :results (get-in data [:resolution :results])
                               :header (if multi-tenant?
                                         (str tenant " / " root-label " / " label)
                                         (str root-label " / " label))})))
                         vec)]
    (into tenant-cols (global-columns successful-results))))

(defn- matrix-grid-template-columns
  [roots]
  (str/join
   " "
   (cons "minmax(150px, 190px)"
         (map (fn [root]
                (if (= root :dataset)
                  "minmax(280px, 1.45fr)"
                  "minmax(170px, 0.9fr)"))
              roots))))

(defn- node-parent-id
  [node]
  (get-in node [:config.node/parent :config.node/id]))

(defn- node-sort-key
  [node]
  [(str/lower-case (or (:config.node/label node)
                       (:config.node/tenant-config-key node)
                       (:config.node/id node)))
   (:config.node/id node)])

(defn selector-tree
  [nodes]
  (let [nodes-by-id (into {} (map (juxt :config.node/id identity)) nodes)
        child-groups (reduce (fn [acc node]
                               (let [parent-id (node-parent-id node)
                                     normalized-parent-id (when (contains? nodes-by-id parent-id)
                                                            parent-id)]
                                 (update acc normalized-parent-id (fnil conj []) node)))
                             {}
                             nodes)
        ordered-child-groups (into {}
                                   (map (fn [[parent-id children]]
                                          [parent-id (sort-by node-sort-key children)]))
                                   child-groups)]
    (letfn [(build [parent-id]
              (mapv (fn [node]
                      (let [node-id (:config.node/id node)]
                        {:node node
                         :children (build node-id)}))
                    (get ordered-child-groups parent-id [])))]
      (build nil))))

(defn selector-tree-rows
  [nodes expanded-node-cells tenant root]
  (let [tree (selector-tree nodes)]
    (letfn [(walk [depth branches]
              (mapcat (fn [{:keys [node children]}]
                        (let [tenant-config-key (:config.node/tenant-config-key node)
                              node-cell-id [tenant root tenant-config-key]
                              child-count (count children)
                              expanded? (contains? expanded-node-cells node-cell-id)]
                          (cons {:node node
                                 :depth depth
                                 :has-children? (pos? child-count)
                                 :child-count child-count
                                 :expanded? expanded?}
                                (when expanded?
                                  (walk (inc depth) children)))))
                      branches))]
      (vec (walk 0 tree)))))

(defn- child-nodes-label
  [child-count expanded?]
  (let [node-label (if (= child-count 1) "child node" "child nodes")]
    (if expanded?
      (str "Hide " child-count " " node-label)
      (str child-count " " node-label))))

(defn- initial-edit-raw-value
  "Render a decoded value into the editable textarea form. For :string types
   we show the raw string so edits round-trip without accumulating quotes;
   for other types we pr-str so edn / numbers / booleans remain parseable."
  ([value] (initial-edit-raw-value value nil))
  ([value value-type]
   (cond
     (nil? value) ""
     (= :string value-type) (str value)
     :else (pr-str value))))

(def ^:private path-filter-debounce-ms 180)

#?(:cljs
   (defn- debounce-path-filter!
     [!timer-id !path-filter value]
     (when-let [timer-id @!timer-id]
       (js/clearTimeout timer-id))
     (reset! !timer-id
             (js/setTimeout
              (fn []
                (reset! !path-filter value)
                (reset! !timer-id nil))
              path-filter-debounce-ms))))

#?(:clj
   (defn- debounce-path-filter!
     [_ !path-filter value]
     (reset! !path-filter value)))

(e/defn NodeValueEditModal
  [editing-cell !editing-cell !mutation]
  (e/client
   (let [{:keys [tenant root path node-id value draft-raw definition column-kind draft-changelog]}
         editing-cell
         global-col? (= column-kind :global)
         ownership (or (:config-def/ownership definition) :fork)
         inherit? (= ownership :inherit)
         value-type (:config-def/value-type definition)
         raw-value (or draft-raw (initial-edit-raw-value value value-type))
         changelog-text (or draft-changelog "")]
     (dom/div
      (dom/props {:style {:position "fixed"
                          :top "0"
                          :left "0"
                          :width "100%"
                          :height "100%"
                          :background "rgba(0,0,0,0.5)"
                          :display "flex"
                          :align-items "center"
                          :justify-content "center"
                          :z-index "100"}})
      (dom/div
       (dom/props {:style {:background "white"
                           :padding "1.5rem"
                           :border-radius "8px"
                           :width "440px"
                           :max-width "92%"}})
       (dom/div
        (dom/props {:style {:font-weight "600" :margin-bottom "0.5rem"
                            :display "flex" :align-items "center" :gap "0.35rem"}})
        (dom/span (dom/text (if global-col? "Edit global " "Edit ")))
        (dom/span (dom/text path))
        (OwnershipBadge ownership))
       (dom/div
        (dom/props {:style {:font-size "0.75rem"
                            :color "#6b7280"
                            :margin-bottom "1rem"
                            :white-space "pre-line"}})
        (dom/text (if global-col?
                    (str "Layer: global\nRoot: " (name root) "\nNode: " node-id)
                    (str "Tenant: " tenant "\nRoot: " (name root) "\nNode: " node-id))))
       (dom/div
        (dom/label (dom/props {:style diagnostics-label-style}) (dom/text "Value"))
        (dom/textarea
         (dom/props {:style (merge textarea-style {:min-height "100px"
                                                   :width "100%"
                                                   :box-sizing "border-box"})
                     :value raw-value})
         (dom/On "input"
                 (fn [ev]
                   (swap! !editing-cell assoc :draft-raw (.. ev -target -value)))
                 nil)))
       (when global-col?
         (dom/div
          (dom/props {:style {:margin-top "0.75rem"}})
          (dom/label (dom/props {:style diagnostics-label-style})
                     (dom/text "Changelog (required)"))
          (dom/input
           (dom/props {:type "text"
                       :value changelog-text
                       :placeholder "Why this change? Who is affected?"
                       :style (merge input-style {:width "100%"
                                                  :box-sizing "border-box"})})
           (dom/On "input"
                   (fn [ev]
                     (swap! !editing-cell assoc :draft-changelog (.. ev -target -value)))
                   nil))))
       (dom/div
        (dom/props {:style {:display "flex"
                            :gap "0.5rem"
                            :flex-wrap "wrap"
                            :margin-top "1rem"
                            :justify-content "flex-end"}})
        (dom/button
         (dom/props {:style (button-style :secondary)})
         (dom/text "Cancel")
         (let [[tok _] (e/Token (dom/On "click" identity nil))]
           (when tok
             (reset! !editing-cell nil)
             (tok))))

        ;; Pin-to-current-global (tenant column, inherit-owned, no local value)
        (when (and (not global-col?) inherit?)
          (dom/button
           (dom/props {:style (button-style :secondary)
                       :title "Copy the current global value into this tenant's tree and pin it."})
           (dom/text "Pin to current global")
           (let [[tok _] (e/Token (dom/On "click" identity nil))]
             (when tok
               (reset! !mutation {:op :pin-tenant-value
                                  :tenant tenant
                                  :root root
                                  :node-id node-id
                                  :path path})
               (tok)))))

        ;; Revert-to-inherited (tenant column, inherit-owned, local value exists)
        (when (and (not global-col?) inherit? (some? value))
          (dom/button
           (dom/props {:style (button-style :secondary)
                       :title "Remove this tenant value and read from global on next resolve."})
           (dom/text "Revert to inherited")
           (let [[tok _] (e/Token (dom/On "click" identity nil))]
             (when tok
               (reset! !mutation {:op :unpin-tenant-value
                                  :tenant tenant
                                  :root root
                                  :node-id node-id
                                  :path path})
               (tok)))))

        ;; Save: global-edit vs tenant-edit
        (dom/button
         (dom/props {:style (button-style :primary)})
         (dom/text (if global-col? "Save global" "Save"))
         (let [[tok _] (e/Token (dom/On "click" identity nil))]
           (when tok
             (if global-col?
               (reset! !mutation {:op :set-global-value
                                  :root root
                                  :path path
                                  :raw-value raw-value
                                  :changelog changelog-text})
               (reset! !mutation {:op :set-node-value
                                  :tenant tenant
                                  :root root
                                  :node-id node-id
                                  :path path
                                  :raw-value raw-value}))
             (tok))))

        (when (not global-col?)
          (dom/button
           (dom/props {:style (button-style :danger)})
           (dom/text "Reset")
           (let [[tok _] (e/Token (dom/On "click" identity nil))]
             (when tok
               (reset! !mutation {:op :delete-node-value
                                  :tenant tenant
                                  :root root
                                  :node-id node-id
                                  :path path})
               (tok)))))))))))

;; =============================================================================
;; Main Components
;; =============================================================================

(e/defn FocusedInheritanceEditor
  "Render the shared inheritance/value editor for an explicit set of node selections."
  [selections !refresh-counter user-id empty-message column-mode]
  (e/client
   (let [!path-filter-input (atom "")
         !path-filter (atom "")
         !path-filter-timer (atom nil)
         !category (atom :all)
         !state (atom :loading)
         !result (atom nil)
         !error (atom nil)
         !editing-cell (atom nil)
         !mutation (atom nil)
         !loaded-request-key (atom nil)
         path-filter-input (e/watch !path-filter-input)
         path-filter (e/watch !path-filter)
         category (e/watch !category)
         state (e/watch !state)
         result (e/watch !result)
         error (e/watch !error)
         editing-cell (e/watch !editing-cell)
         mutation (e/watch !mutation)
         refresh-counter (e/watch !refresh-counter)
         loaded-request-key (e/watch !loaded-request-key)
         request-key [refresh-counter selections]]

     (when (not= request-key loaded-request-key)
       (reset! !loaded-request-key request-key)
       (reset! !result nil)
       (reset! !error nil)
       (reset! !state :loading))

     (case state
       :loading
       (let [server-result (e/server
                            (let [[request-refresh-counter requested-selections] (e/client request-key)
                                  _ request-refresh-counter]
                              (e/Offload #(try
                                            (common/get-inheritance-editor-comparison-data
                                             {:selections requested-selections})
                                            (catch #?(:clj Exception :cljs :default) e
                                              {:status :error
                                               :error (or (ex-message e)
                                                          "Failed to load inheritance data")})))))]
         (e/client
          (if (= :success (:status server-result))
            (do
              (reset! !result (:data server-result))
              (reset! !error nil)
              (reset! !state :done))
            (do
              (reset! !result nil)
              (reset! !error (or (:error server-result) "Failed to load inheritance data"))
              (reset! !state :error)))))
       nil)

     (when mutation
       (let [server-result (e/server
                            (e/Offload #(try
                                          (common/mutate-config-tree!
                                           (merge mutation
                                                  {:tenant (:tenant mutation)
                                                   :root (:root mutation)
                                                   :user-id user-id}))
                                          {:status :success}
                                          (catch #?(:clj Exception :cljs :default) e
                                            {:status :error
                                             :error (or (ex-message e) "Update failed")}))))]
         (e/client
          (if (= :success (:status server-result))
            (do
              (reset! !mutation nil)
              (reset! !editing-cell nil)
              (reset! !state :loading))
            (do
              (reset! !error (:error server-result))
              (reset! !mutation nil))))))

     (let [comparison-results (vec (or (get-in result [:results]) []))
           definitions (merge-definitions comparison-results)
           categories (comparison-categories definitions)
           category-valid? (or (= category :all)
                               (some #(= (category-name %) (category-name category)) categories))
           _ (when-not category-valid?
               (reset! !category :all))
           filtered-definitions (filter-definitions definitions
                                                   (if category-valid? category :all)
                                                   path-filter)
           grouped-defs (common/group-paths-by-segment filtered-definitions)
           columns (case column-mode
                     :selected-nodes (selected-node-columns comparison-results)
                     (comparison-columns comparison-results))
           tenant-errors (->> comparison-results
                              (keep (fn [{:keys [tenant root tenant-config-key node-id status error]}]
                                      (when (and (= :error status) error)
                                        (str tenant
                                             " / "
                                             (name root)
                                             " / "
                                             (or tenant-config-key node-id "<unknown>")
                                             ": "
                                             error))))
                              vec)]
       (dom/div
        (when error
          (dom/div
           (dom/props {:style {:margin-top "0.75rem"
                               :padding "0.75rem"
                               :background "#fef2f2"
                               :border "1px solid #fecaca"
                               :border-radius "6px"
                               :font-size "0.875rem"
                               :color "#b91c1c"}})
           (dom/text error)))

        (when (seq tenant-errors)
          (dom/div
           (dom/props {:style {:margin-top "0.75rem"
                               :padding "0.75rem"
                               :background "#fff7ed"
                               :border "1px solid #fdba74"
                               :border-radius "6px"
                               :font-size "0.875rem"
                               :color "#9a3412"}})
           (e/for [tenant-error (e/diff-by identity tenant-errors)]
             (dom/div (dom/text tenant-error)))))

        (when (= state :loading)
          (dom/div
           (dom/props {:style {:margin-top "0.75rem"
                               :font-size "0.875rem"
                               :color "#1d4ed8"}})
           (dom/text "Loading inheritance view...")))

        (when (and (= state :done) (empty? columns) (empty? tenant-errors))
          (dom/div
           (dom/props {:style {:margin-top "0.75rem"
                               :padding "0.875rem"
                               :border "1px solid #e5e7eb"
                               :border-radius "8px"
                               :background "#ffffff"
                               :font-size "0.875rem"
                               :color "#6b7280"}})
           (dom/text (or empty-message
                         "No matching configuration nodes were available."))))

        (when (seq columns)
          (dom/div
           (dom/props {:style {:margin-top "1rem"}})

           (dom/div
            (dom/props {:style {:display "flex"
                                :justify-content "space-between"
                                :align-items "flex-end"
                                :gap "1rem"
                                :flex-wrap "wrap"
                                :margin-bottom "1rem"}})
            (dom/div
             (dom/props {:style {:flex "1 1 280px"
                                 :min-width "240px"}})
             (dom/label (dom/props {:style diagnostics-label-style}) (dom/text "Filter Paths"))
             (dom/input
              (dom/props {:type "text"
                          :value path-filter-input
                          :placeholder "Filter by path or category"
                          :style (merge input-style
                                        {:width "100%"
                                         :box-sizing "border-box"})})
              (dom/On "input"
                      (fn [ev]
                        (let [value (.. ev -target -value)]
                          (reset! !path-filter-input value)
                          (debounce-path-filter! !path-filter-timer !path-filter value)))
                      nil)))
            (dom/div
             (dom/props {:style {:flex "1 1 520px"
                                 :min-width "280px"}})
             (CategoryTabsWithStats categories
                                    !category
                                    (count filtered-definitions)
                                    (count grouped-defs)
                                    (count columns)))
            (dom/div
             (dom/props {:style {:flex "0 0 auto"
                                 :align-self "center"}})
             (GlobalVersionPill (get result :global-version))))

           (if (empty? filtered-definitions)
             (dom/div
              (dom/props {:style {:padding "0.875rem"
                                  :border "1px solid #e5e7eb"
                                  :border-radius "8px"
                                  :background "#ffffff"
                                  :font-size "0.875rem"
                                  :color "#6b7280"}})
              (dom/text "No configuration paths match the current filters."))
             (dom/div
              (dom/props {:style (merge inheritance-table-style
                                        {"--col-count" (count columns)})})

              (dom/div
               (dom/props {:style inheritance-header-style})
               (dom/div
                (dom/props {:style (merge inheritance-cell-style {:background "#f9fafb" :font-weight "600"})})
                (dom/text (t :config/path)))
               (e/for [column (e/diff-by :column-id columns)]
                 (dom/div
                  (dom/props {:style (merge inheritance-cell-style {:background "#f9fafb"
                                                                    :font-weight "600"
                                                                    :text-align "center"})})
                  (dom/text (:header column))
                  (when (and (= :tenant (:column-kind column))
                             (not= "__global__" (:tenant column)))
                    (dom/div
                     (dom/props {:style {:margin-top "0.375rem"}})
                     (dom/button
                      (dom/props {:style (button-style :secondary :small)
                                  :title (str "Pin all current global values into "
                                              (:tenant column) "'s "
                                              (name (:root column))
                                              " tree. Future global edits won't propagate "
                                              "to this tenant for those paths until unpinned.")})
                      (dom/text "📌 Pin all globals")
                      (let [[tok _] (e/Token (dom/On "click" identity nil))]
                        (when tok
                          (reset! !mutation {:op :pin-all-globals-for-tenant
                                             :tenant (:tenant column)
                                             :root (:root column)})
                          (tok)))))))))

              (let [master-key (e/server (common/get-master-key))
                    global-version (get result :global-version)]
                (e/for [[group-name group-defs] (e/diff-by first grouped-defs)]
                  (dom/div
                   (dom/props {:style inheritance-group-header-style})
                   (dom/text group-name))
                  (e/for [definition (e/diff-by :config-def/path group-defs)]
                    (let [path (:config-def/path definition)
                          ownership (or (:config-def/ownership definition) :fork)
                          def-root (:config-def/root definition)]
                      (dom/div
                       (dom/props {:style (merge inheritance-path-cell-style
                                                 {:display "flex"
                                                  :align-items "center"
                                                  :justify-content "space-between"
                                                  :gap "0.5rem"})})
                       (dom/span (dom/props {:style {:overflow "hidden"
                                                     :text-overflow "ellipsis"}})
                                 (dom/text path))
                       (OwnershipBadge ownership))
                      (e/for [column (e/diff-by :column-id columns)]
                        (let [column-kind (or (:column-kind column) :tenant)
                              column-root (:root column)
                              node-id (:node-id column)
                              applies? (or (= column-kind :tenant)
                                           ;; Only render global cells for inherit-owned rows and
                                           ;; only in the column matching this definition's root.
                                           (and (= column-kind :global)
                                                (= :inherit ownership)
                                                (= def-root column-root)))
                              node-value (when applies?
                                           (get-in (:values-by-node column) [node-id path]))
                              display-value (when node-value
                                              (e/server
                                               (common/decode-node-value node-value definition master-key)))
                              winning-node-id (get-in (:results column) [path :trace :winning-node])
                              winner? (and applies? (= node-id winning-node-id))]
                          (if applies?
                            (InheritanceValueCell
                             path node-id display-value winner?
                             (e/fn []
                               (reset! !editing-cell {:tenant (:tenant column)
                                                      :root (:root column)
                                                      :path path
                                                      :node-id node-id
                                                      :value display-value
                                                      :draft-raw (initial-edit-raw-value display-value
                                                                                         (:config-def/value-type definition))
                                                      :definition definition
                                                      :column-kind column-kind}))
                             {:column-kind column-kind
                              :ownership ownership
                              :value-entity node-value
                              :version global-version
                              :value-type (:config-def/value-type definition)
                              :tenant (:tenant column)})
                            (dom/div
                             (dom/props {:style (merge inheritance-cell-style
                                                       {:color "#d1d5db"
                                                        :text-align "center"
                                                        :background "#fafafa"})})
                             (dom/text "—")))))))))))

           (when editing-cell
             (NodeValueEditModal editing-cell !editing-cell !mutation))
     )))))))

(e/defn ConfigInheritanceEditor [tenants !refresh-counter user-id]
  (e/client
   (let [!selected-node-cells (atom #{})
         !expanded-tree-nodes (atom #{})
         !path-filter-input (atom "")
         !path-filter (atom "")
         !path-filter-timer (atom nil)
         !category (atom :all)
         !state (atom :loading)
         !result (atom nil)
         !error (atom nil)
         !editing-cell (atom nil) ; {:tenant "tenant" :root :runtime :path "path" :node-id "node-id" :value "value"}
         selected-node-cells (e/watch !selected-node-cells)
         expanded-tree-nodes (e/watch !expanded-tree-nodes)
         path-filter-input (e/watch !path-filter-input)
         path-filter (e/watch !path-filter)
         category (e/watch !category)
         state (e/watch !state)
         result (e/watch !result)
         error (e/watch !error)
         editing-cell (e/watch !editing-cell)
         _refresh-counter (e/watch !refresh-counter)]

     (case state
       :loading
       (let [comparison-selections (selected-comparison-selections selected-node-cells)
             matrix-result (e/server
                            (e/Offload #(common/get-inheritance-selector-matrix-data
                                         {:tenants tenants})))
             comparison-result (e/server
                                 (e/Offload #(common/get-inheritance-editor-comparison-data
                                              {:selections comparison-selections})))]
         (e/client
          (if (and (= :success (:status matrix-result))
                   (= :success (:status comparison-result)))
            (do
              (reset! !result {:matrix (:data matrix-result)
                               :comparison (:data comparison-result)})
              (reset! !error nil)
              (reset! !state :done))
            (do
              (reset! !result nil)
              (reset! !error (or (:error matrix-result)
                                 (:error comparison-result)
                                 "Failed to load inheritance data"))
              (reset! !state :error)))))
       nil)

     (let [!mutation (atom nil)
           mutation (e/watch !mutation)]
       (when mutation
         (let [server-result (e/server
                              (e/Offload #(try
                                            (common/mutate-config-tree!
                                             (merge mutation
                                                    {:tenant (:tenant mutation)
                                                     :root (:root mutation)
                                                     :user-id user-id}))
                                            {:status :success}
                                            (catch #?(:clj Exception :cljs :default) e
                                              {:status :error
                                               :error (or (ex-message e) "Update failed")}))))]
           (e/client
            (if (= :success (:status server-result))
              (do
                (reset! !mutation nil)
                (reset! !editing-cell nil)
                (reset! !state :loading))
              (do
                (reset! !error (:error server-result))
                (reset! !mutation nil))))))

         (let [matrix-data (or (:matrix result) {:roots []
                                                 :rows []})
             roots (vec (:roots matrix-data))
             matrix-columns (matrix-grid-template-columns roots)
             rows (vec (:rows matrix-data))
             comparison-results (vec (or (get-in result [:comparison :results]) []))
             comparison-selections (selected-comparison-selections selected-node-cells)
             definitions (merge-definitions comparison-results)
             categories (comparison-categories definitions)
             category-valid? (or (= category :all)
                                 (some #(= (category-name %) (category-name category)) categories))
             _ (when-not category-valid?
                 (reset! !category :all))
             filtered-definitions (filter-definitions definitions
                                                     (if category-valid? category :all)
                                                     path-filter)
             grouped-defs (common/group-paths-by-segment filtered-definitions)
             columns (comparison-columns comparison-results)
             tenant-errors (->> comparison-results
                                (keep (fn [{:keys [tenant root tenant-config-key status error]}]
                                        (when (and (= :error status) error)
                                          (str tenant " / " (name root) " / " tenant-config-key ": " error))))
                                vec)]
         (dom/div
          (dom/props {:style {:margin-bottom "2rem"}})

          (dom/div
           (dom/props {:style {:margin-bottom "0.35rem"
                               :font-size "0.75rem"
                               :color "#6b7280"}})
           (dom/text "Click any node in the mini tree to add or remove that tenant/root/node combination from the comparison."))

          (dom/div
           (dom/props {:style {:border "1px solid #e5e7eb"
                               :border-radius "8px"
                               :overflow "hidden"
                               :background "#ffffff"}})
           (dom/div
            (dom/props {:style {:display "grid"
                                :grid-template-columns matrix-columns
                                :background "#f9fafb"
                                :border-bottom "1px solid #e5e7eb"}})
            (dom/div
             (dom/props {:style (merge inheritance-cell-style {:background "#f9fafb"
                                                               :font-weight "600"
                                                               :padding "0.5rem 0.75rem"})})
             (dom/text "Tenant"))
            (e/for [root (e/diff-by identity roots)]
              (dom/div
               (dom/props {:style (merge inheritance-cell-style {:background "#f9fafb"
                                                                 :font-weight "600"
                                                                 :padding "0.5rem 0.75rem"
                                                                 :text-transform "capitalize"})})
               (dom/text (name root)))))
           (e/for [{:keys [tenant cells]} (e/diff-by :tenant rows)]
             (dom/div
              (dom/props {:style {:display "grid"
                                  :grid-template-columns matrix-columns
                                  :border-bottom "1px solid #f3f4f6"}})
              (dom/div
               (dom/props {:style (merge inheritance-cell-style {:background "#fcfcfc"
                                                                 :font-weight "600"
                                                                 :padding "0.5rem 0.75rem"
                                                                 :vertical-align "top"})})
               (dom/text tenant))
              (e/for [root (e/diff-by identity roots)]
                (let [nodes (get-in cells [root :nodes])
                      tree-rows (selector-tree-rows nodes expanded-tree-nodes tenant root)
                      has-nodes? (seq nodes)]
                  (dom/div
                   (dom/props {:style {:padding "0.5rem"
                                       :border-right "1px solid #f3f4f6"
                                       :background (if has-nodes? "white" "#f9fafb")
                                       :display "grid"
                                       :align-content "start"
                                       :gap "0.25rem"
                                       :min-height "0"}})
                   (if has-nodes?
                     (e/for [{:keys [node depth has-children? child-count expanded?]} (e/diff-by (fn [{:keys [node]}] (:config.node/id node)) tree-rows)]
                       (let [tenant-config-key (:config.node/tenant-config-key node)
                             node-cell-id [tenant root tenant-config-key]
                             label (or (:config.node/label node) tenant-config-key)
                             show-key? (not= label tenant-config-key)
                             indent-rem (* depth 0.9)
                             selected? (contains? selected-node-cells node-cell-id)]
                         (dom/div
                          (dom/props {:style {:padding-left (str indent-rem "rem")}})
                          (dom/div
                           (dom/props {:style {:position "relative"
                                               :width "100%"}})
                           (dom/button
                            (dom/props {:style {:width "100%"
                                                :text-align "left"
                                                :padding (if has-children?
                                                           (if show-key? "0.3rem 0.45rem 1.35rem" "0.25rem 0.45rem 1.25rem")
                                                           (if show-key? "0.3rem 0.45rem" "0.25rem 0.45rem"))
                                                :border-radius "8px"
                                                :border (if selected?
                                                          "1px solid #3b82f6"
                                                          "1px solid #d1d5db")
                                                :background (if selected?
                                                              "#eff6ff"
                                                              "#ffffff")
                                                :color (if selected?
                                                         "#1d4ed8"
                                                         "#374151")
                                                :cursor "pointer"
                                                :display "grid"
                                                :gap (if show-key? "0.1rem" "0")
                                                :min-width "0"
                                                :box-shadow (when (pos? depth) "inset 3px 0 0 #e5e7eb")}})
                            (dom/div
                             (dom/props {:style {:display "flex"
                                                 :align-items "center"
                                                 :gap "0.35rem"
                                                 :min-width "0"}})
                             (when (pos? depth)
                               (dom/span
                                (dom/props {:style {:font-size "0.72rem"
                                                    :color "#9ca3af"
                                                    :flex "0 0 auto"}})
                                (dom/text ">")))
                             (dom/span
                              (dom/props {:style {:font-size "0.76rem"
                                                  :font-weight "600"
                                                  :line-height "1.1"
                                                  :white-space "normal"
                                                  :overflow-wrap "anywhere"
                                                  :min-width "0"}})
                              (dom/text label)))
                            (when show-key?
                              (dom/div
                               (dom/props {:style {:font-size "0.67rem"
                                                   :line-height "1.05"
                                                   :color (if selected?
                                                            "#2563eb"
                                                            "#6b7280")
                                                   :white-space "normal"
                                                   :overflow-wrap "anywhere"}})
                               (dom/text tenant-config-key)))
                            (let [[tok _] (e/Token (dom/On "click" identity nil))]
                              (when tok
                                (swap! !selected-node-cells
                                       (fn [selected]
                                         (if (contains? selected node-cell-id)
                                           (disj selected node-cell-id)
                                           (conj selected node-cell-id))))
                                (reset! !editing-cell nil)
                                (reset! !state :loading)
                                (tok))))
                           (when has-children?
                             (dom/button
                              (dom/props {:style {:position "absolute"
                                                  :right "0.45rem"
                                                  :bottom "0.25rem"
                                                  :border "none"
                                                  :background "transparent"
                                                  :padding "0"
                                                  :font-size "0.66rem"
                                                  :line-height "1"
                                                  :color "#6b7280"
                                                  :text-decoration "underline"
                                                  :cursor "pointer"}})
                              (dom/text (child-nodes-label child-count expanded?))
                              (let [[tok _] (e/Token (dom/On "click"
                                                             (fn [event]
                                                               (.stopPropagation event)
                                                               event)
                                                             nil))]
                                (when tok
                                  (swap! !expanded-tree-nodes
                                         (fn [expanded]
                                           (if (contains? expanded node-cell-id)
                                             (disj expanded node-cell-id)
                                             (conj expanded node-cell-id))))
                                  (tok)))))))))
                     (dom/div
                      (dom/props {:style {:font-size "0.8rem"
                                          :color "#9ca3af"
                                          :line-height "1"}})
                      (dom/text "-"))))))))

          (when error
            (dom/div
             (dom/props {:style {:margin-top "0.75rem"
                                 :padding "0.75rem"
                                 :background "#fef2f2"
                                 :border "1px solid #fecaca"
                                 :border-radius "6px"
                                 :font-size "0.875rem"
                                 :color "#b91c1c"}})
             (dom/text error)))

          (when (seq tenant-errors)
            (dom/div
             (dom/props {:style {:margin-top "0.75rem"
                                 :padding "0.75rem"
                                 :background "#fff7ed"
                                 :border "1px solid #fdba74"
                                 :border-radius "6px"
                                 :font-size "0.875rem"
                                 :color "#9a3412"}})
             (e/for [tenant-error (e/diff-by identity tenant-errors)]
               (dom/div (dom/text tenant-error)))))

          (when (= state :loading)
            (dom/div
             (dom/props {:style {:margin-top "0.75rem"
                                 :font-size "0.875rem"
                                 :color "#1d4ed8"}})
             (dom/text "Loading inheritance view...")))

          (when (and result (empty? comparison-selections))
            (dom/div
             (dom/props {:style {:margin-top "0.75rem"
                                 :font-size "0.875rem"
                                 :color "#6b7280"}})
             (dom/text "Click one or more nodes in the selector table to inspect and compare inheritance chains.")))

          (when (and result (seq comparison-selections) (seq columns))
            (dom/div
             (dom/props {:style {:margin-top "1rem"}})

             (dom/div
              (dom/props {:style {:display "flex"
                                  :justify-content "space-between"
                                  :align-items "flex-end"
                                  :gap "1rem"
                                  :flex-wrap "wrap"
                                  :margin-top "1.5rem"
                                  :margin-bottom "1rem"}})
              (dom/div
               (dom/props {:style {:flex "1 1 280px"
                                   :min-width "240px"}})
               (dom/label (dom/props {:style diagnostics-label-style}) (dom/text "Filter Paths"))
               (dom/input
                (dom/props {:type "text"
                            :value path-filter-input
                            :placeholder "Filter by path or category"
                            :style (merge input-style
                                          {:width "100%"
                                           :box-sizing "border-box"})})
                (dom/On "input"
                        (fn [ev]
                          (let [value (.. ev -target -value)]
                            (reset! !path-filter-input value)
                            (debounce-path-filter! !path-filter-timer !path-filter value)))
                        nil)))
              (dom/div
               (dom/props {:style {:flex "1 1 520px"
                                   :min-width "280px"}})
               (CategoryTabsWithStats categories
                                      !category
                                      (count filtered-definitions)
                                      (count grouped-defs)
                                      (count columns)))
              (dom/div
               (dom/props {:style {:flex "0 0 auto"
                                   :align-self "center"}})
               (GlobalVersionPill (get-in result [:comparison :global-version]))))

             (if (empty? filtered-definitions)
               (dom/div
                (dom/props {:style {:padding "0.875rem"
                                    :border "1px solid #e5e7eb"
                                    :border-radius "8px"
                                    :background "#ffffff"
                                    :font-size "0.875rem"
                                    :color "#6b7280"}})
                (dom/text "No configuration paths match the current filters."))
               (dom/div
                (dom/props {:style (merge inheritance-table-style
                                          {"--col-count" (count columns)})})

                (dom/div
                 (dom/props {:style inheritance-header-style})
                 (dom/div
                  (dom/props {:style (merge inheritance-cell-style {:background "#f9fafb" :font-weight "600"})})
                  (dom/text (t :config/path)))
                 (e/for [column (e/diff-by :column-id columns)]
                   (dom/div
                    (dom/props {:style (merge inheritance-cell-style {:background "#f9fafb"
                                                                      :font-weight "600"
                                                                      :text-align "center"})})
                    (dom/text (:header column)))))

                (let [master-key (e/server (common/get-master-key))
                      global-version (get-in result [:comparison :global-version])]
                  (e/for [[group-name group-defs] (e/diff-by first grouped-defs)]
                    (dom/div
                     (dom/props {:style inheritance-group-header-style})
                     (dom/text group-name))
                    (e/for [definition (e/diff-by :config-def/path group-defs)]
                      (let [path (:config-def/path definition)
                            ownership (or (:config-def/ownership definition) :fork)
                            def-root (:config-def/root definition)]
                        (dom/div
                         (dom/props {:style (merge inheritance-path-cell-style
                                                   {:display "flex"
                                                    :align-items "center"
                                                    :justify-content "space-between"
                                                    :gap "0.5rem"})})
                         (dom/span (dom/props {:style {:overflow "hidden"
                                                       :text-overflow "ellipsis"}})
                                   (dom/text path))
                         (OwnershipBadge ownership))
                        (e/for [column (e/diff-by :column-id columns)]
                          (let [column-kind (or (:column-kind column) :tenant)
                                column-root (:root column)
                                node-id (:node-id column)
                                applies? (or (= column-kind :tenant)
                                             (and (= column-kind :global)
                                                  (= :inherit ownership)
                                                  (= def-root column-root)))
                                node-value (when applies?
                                             (get-in (:values-by-node column) [node-id path]))
                                display-value (when node-value
                                                (e/server
                                                 (common/decode-node-value node-value definition master-key)))
                                winning-node-id (get-in (:results column) [path :trace :winning-node])
                                winner? (and applies? (= node-id winning-node-id))]
                            (if applies?
                              (InheritanceValueCell
                               path node-id display-value winner?
                               (e/fn []
                                 (reset! !editing-cell {:tenant (:tenant column)
                                                        :root (:root column)
                                                        :path path
                                                        :node-id node-id
                                                        :value display-value
                                                        :draft-raw (initial-edit-raw-value display-value
                                                                                           (:config-def/value-type definition))
                                                        :definition definition
                                                        :column-kind column-kind}))
                               {:column-kind column-kind
                                :ownership ownership
                                :value-entity node-value
                                :version global-version
                                :value-type (:config-def/value-type definition)
                                :tenant (:tenant column)})
                              (dom/div
                               (dom/props {:style (merge inheritance-cell-style
                                                         {:color "#d1d5db"
                                                          :text-align "center"
                                                          :background "#fafafa"})})
                               (dom/text "—"))))))))))))

             (when editing-cell
               (NodeValueEditModal editing-cell !editing-cell !mutation))
))))))))
