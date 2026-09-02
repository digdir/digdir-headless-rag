(ns digdir.config.ui.ownership-migration
  "Promotion wizard for fork-owned definitions → :inherit-owned. Guides an
   operator through inspection of tenant values, pick of a candidate global
   value, choice of preservation strategy (pin-all vs revert-all), preview,
   and apply. Called from the Global Defaults tab."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [digdir.config.ui.common :as common]
            [digdir.config.ui.styles :as styles]))

(def ^:private button-style styles/button-style)
(def ^:private input-style styles/input-style)
(def ^:private diagnostics-label-style styles/diagnostics-label-style)

(def ^:private modal-backdrop-style
  {:position "fixed" :top "0" :left "0" :width "100%" :height "100%"
   :background "rgba(0,0,0,0.5)"
   :display "flex" :align-items "center" :justify-content "center"
   :z-index "100"})

(def ^:private modal-panel-style
  {:background "white"
   :padding "1.5rem"
   :border-radius "8px"
   :width "520px"
   :max-width "95%"
   :max-height "85vh"
   :overflow "auto"})

(def ^:private step-badge-style
  {:display "inline-block" :padding "1px 8px" :font-size "0.65rem"
   :font-weight "600" :letter-spacing "0.04em" :text-transform "uppercase"
   :background "#eef2ff" :color "#3730a3" :border-radius "9999px"
   :margin-right "0.4rem"})

(def ^:private cluster-row-style
  {:display "grid"
   :grid-template-columns "minmax(120px,1fr) 60px 1fr"
   :gap "0.5rem" :padding "0.45rem 0.6rem"
   :border "1px solid #e5e7eb" :border-radius "6px"
   :cursor "pointer" :margin-bottom "0.35rem"})

(def ^:private cluster-row-selected-style
  (merge cluster-row-style {:background "#eef2ff"
                            :border "1px solid #6366f1"}))

(defn- initial-wizard-state
  [definition]
  {:step :loading
   :path (:config-def/path definition)
   :definition definition
   :inspection nil
   :candidate-value nil
   :strategy :pin-all
   :changelog ""
   :result nil
   :error nil})

(e/defn StepBadge [text]
  (dom/span
   (dom/props {:style step-badge-style})
   (dom/text text)))

(e/defn WizardHeader [path step-label]
  (dom/div
   (dom/props {:style {:font-weight "600" :margin-bottom "0.25rem"
                       :display "flex" :align-items "center"}})
   (StepBadge step-label)
   (dom/span (dom/text "Promote to global: "))
   (dom/span (dom/props {:style {:font-family "monospace"}})
             (dom/text path)))
  (dom/div
   (dom/props {:style {:font-size "0.75rem" :color "#6b7280"
                       :margin-bottom "0.9rem"}})
   (dom/text "Converts this fork-owned definition into an inherit-owned one. Every tenant's effective value is preserved.")))

(e/defn InspectStep [!state]
  (let [{:keys [inspection candidate-value]} (e/watch !state)
        clusters (:clusters inspection)]
    (WizardHeader (:path (e/watch !state)) "1. Inspect")
    (dom/div
     (dom/props {:style {:font-size "0.8rem" :color "#4b5563"
                         :margin-bottom "0.5rem"}})
     (dom/text (str (:with-value-count inspection) " tenants have an explicit value · "
                    (:without-value-count inspection) " have no value · "
                    (count clusters) " distinct value cluster(s)")))
    (dom/div
     (dom/props {:style {:font-size "0.75rem" :color "#6b7280"
                         :margin-bottom "0.5rem"}})
     (dom/text "Pick the value that should become the new global default. Tenants whose value already matches will inherit; others will be preserved per your choice on the next step."))
    (e/for [{:keys [value tenants count]} (e/diff-by (comp pr-str :value) clusters)]
      (let [selected? (= value candidate-value)]
        (dom/div
         (dom/props {:style (if selected? cluster-row-selected-style cluster-row-style)
                     :title (apply str "tenants: "
                                   (interpose ", " (map :tenant tenants)))})
         (dom/code (dom/props {:style {:font-size "0.8125rem"
                                       :word-break "break-all"}})
                   (dom/text (pr-str value)))
         (dom/div (dom/props {:style {:font-weight "600"
                                      :color "#374151"
                                      :text-align "center"}})
                  (dom/text (str count)))
         (dom/div (dom/props {:style {:font-size "0.75rem"
                                      :color "#6b7280"
                                      :overflow "hidden"
                                      :text-overflow "ellipsis"
                                      :white-space "nowrap"}})
                  (dom/text (apply str (interpose ", " (map :tenant tenants)))))
         (let [[tok _] (e/Token (dom/On "click" identity nil))]
           (when tok
             (swap! !state assoc :candidate-value value)
             (tok))))))))

(e/defn PreserveStep [!state]
  (let [{:keys [inspection candidate-value strategy]} (e/watch !state)
        non-matching (->> (:clusters inspection)
                          (remove #(= (:value %) candidate-value))
                          (mapcat :tenants)
                          (map :tenant)
                          vec)
        matching (->> (:clusters inspection)
                      (filter #(= (:value %) candidate-value))
                      (mapcat :tenants)
                      (map :tenant)
                      vec)]
    (WizardHeader (:path (e/watch !state)) "2. Preserve")
    (dom/div
     (dom/props {:style {:font-size "0.8rem" :color "#4b5563"
                         :margin-bottom "0.6rem"}})
     (dom/span (dom/text (str (count matching) " tenants already have the candidate value — they will inherit.")))
     (dom/br)
     (dom/span (dom/text (str (count non-matching) " tenants have a different value. Choose how to handle them:"))))
    (dom/div
     (dom/props {:style {:display "flex" :flex-direction "column"
                         :gap "0.5rem" :margin-bottom "0.6rem"}})
     (dom/label
      (dom/props {:style {:display "flex" :gap "0.5rem" :cursor "pointer"}})
      (dom/input
       (dom/props {:type "radio" :name "preserve-strategy"
                   :checked (= strategy :pin-all)})
       (dom/On "change" (fn [_] (swap! !state assoc :strategy :pin-all)) nil))
      (dom/span (dom/props {:style {:font-weight "500"}})
                (dom/text "Pin all non-matching (safe default)"))
      (dom/span (dom/props {:style {:font-size "0.7rem" :color "#6b7280"
                                    :margin-left "0.4rem"}})
                (dom/text "— keep each tenant's current value stamped against the new global version")))
     (dom/label
      (dom/props {:style {:display "flex" :gap "0.5rem" :cursor "pointer"}})
      (dom/input
       (dom/props {:type "radio" :name "preserve-strategy"
                   :checked (= strategy :revert-all)})
       (dom/On "change" (fn [_] (swap! !state assoc :strategy :revert-all)) nil))
      (dom/span (dom/props {:style {:font-weight "500"}})
                (dom/text "Revert all to the new global"))
      (dom/span (dom/props {:style {:font-size "0.7rem" :color "#dc2626"
                                    :margin-left "0.4rem"}})
                (dom/text "— replaces each tenant's value with the candidate (irreversible)"))))
    (dom/details
     (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
     (dom/summary (dom/text (str "Affected tenants (" (count non-matching) ")")))
     (dom/div (dom/props {:style {:margin-top "0.35rem"
                                  :font-family "monospace"
                                  :white-space "pre-wrap"}})
              (dom/text (apply str (interpose "\n" non-matching)))))))

(e/defn PreviewStep [!state]
  (let [{:keys [inspection candidate-value strategy changelog]} (e/watch !state)
        non-matching-count (->> (:clusters inspection)
                                (remove #(= (:value %) candidate-value))
                                (mapcat :tenants)
                                count)
        matching-count (->> (:clusters inspection)
                            (filter #(= (:value %) candidate-value))
                            (mapcat :tenants)
                            count)]
    (WizardHeader (:path (e/watch !state)) "3. Preview")
    (dom/div
     (dom/props {:style {:font-size "0.8rem" :color "#374151"
                         :margin-bottom "0.75rem"
                         :padding "0.6rem"
                         :background "#f9fafb"
                         :border-radius "6px"}})
     (dom/div (dom/text "On apply:"))
     (dom/ul
      (dom/props {:style {:margin "0.3rem 0 0 1rem" :padding "0"}})
      (dom/li (dom/text (str "Definition ownership → :inherit")))
      (dom/li (dom/text (str "Global value → " (pr-str candidate-value))))
      (dom/li (dom/text (str matching-count " tenants will inherit from global")))
      (dom/li (dom/text (str non-matching-count " tenants will be "
                             (case strategy
                               :pin-all "pinned to their current value"
                               :revert-all "reverted to the new global"))))))
    (dom/div
     (dom/props {:style {:margin-top "0.5rem"}})
     (dom/label (dom/props {:style diagnostics-label-style})
                (dom/text "Changelog (required — records why in the audit log)"))
     (dom/input
      (dom/props {:type "text"
                  :value (or changelog "")
                  :placeholder "Promoting per operator review"
                  :style (merge input-style {:width "100%" :box-sizing "border-box"})})
      (dom/On "input"
              (fn [ev] (swap! !state assoc :changelog (.. ev -target -value)))
              nil)))))

(e/defn DoneStep [!state]
  (let [{:keys [result]} (e/watch !state)]
    (WizardHeader (:path (e/watch !state)) "✓ Applied")
    (dom/div
     (dom/props {:style {:font-size "0.85rem" :color "#166534"
                         :padding "0.6rem"
                         :background "#dcfce7"
                         :border "1px solid #22c55e"
                         :border-radius "6px"}})
     (dom/div (dom/text (str "Promoted to global v" (:version result))))
     (dom/div (dom/text (str (count (:inherited result)) " tenants now inherit")))
     (dom/div (dom/text (str (count (:pinned result)) " tenants pinned, "
                             (count (:reverted result)) " reverted"))))))

(e/defn WizardButtons [!state On-cancel On-refresh]
  (let [{:keys [step candidate-value changelog error]} (e/watch !state)
        can-next? (case step
                    :inspect (some? candidate-value)
                    :preserve true
                    :preview (seq (clojure.string/trim (or changelog "")))
                    false)]
    (dom/div
     (dom/props {:style {:display "flex" :gap "0.5rem"
                         :justify-content "flex-end"
                         :margin-top "1rem"}})
     (when error
       (dom/div (dom/props {:style {:color "#b91c1c" :font-size "0.8rem"
                                    :margin-right "auto" :align-self "center"}})
                (dom/text error)))

     ;; Back
     (when (contains? #{:preserve :preview} step)
       (dom/button
        (dom/props {:style (button-style :secondary)})
        (dom/text "Back")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok
            (swap! !state assoc :step (case step
                                        :preserve :inspect
                                        :preview :preserve))
            (tok)))))

     ;; Cancel
     (dom/button
      (dom/props {:style (button-style :secondary)})
      (dom/text (if (= step :done) "Close" "Cancel"))
      (let [[tok _] (e/Token (dom/On "click" identity nil))]
        (when tok
          (On-cancel)
          (when (= step :done) (On-refresh))
          (tok))))

     ;; Next / Apply / Done
     (case step
       :inspect
       (dom/button
        (dom/props {:style (if can-next? (button-style :primary) (button-style :secondary))
                    :disabled (not can-next?)})
        (dom/text "Next →")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when (and tok can-next?)
            (swap! !state assoc :step :preserve)
            (tok))))
       :preserve
       (dom/button
        (dom/props {:style (button-style :primary)})
        (dom/text "Next →")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when tok
            (swap! !state assoc :step :preview)
            (tok))))
       :preview
       (dom/button
        (dom/props {:style (if can-next? (button-style :primary) (button-style :secondary))
                    :disabled (not can-next?)})
        (dom/text "Apply")
        (let [[tok _] (e/Token (dom/On "click" identity nil))]
          (when (and tok can-next?)
            (swap! !state assoc :step :applying :error nil)
            (tok))))
       :done nil
       nil))))

(e/defn PromoteToGlobalWizard
  "Multi-step wizard for promoting a fork-owned definition to :inherit-owned.
   `definition` is the full config-def map. `On-close` is called when the
   operator dismisses the wizard. `On-applied` is called after a successful
   apply so the caller can refresh its data."
  [definition On-close On-applied user-id]
  (e/client
   (let [!state (atom (initial-wizard-state definition))
         state (e/watch !state)
         step (:step state)]

     ;; Kick off inspection on mount.
     (when (= step :loading)
       (let [path (:path state)
             server-result (e/server
                            (e/Offload #(try
                                          (common/get-promotion-inspection-data
                                           {:path path})
                                          (catch #?(:clj Exception :cljs :default) e
                                            {:status :error
                                             :error (or (ex-message e) "inspection failed")}))))]
         (e/client
          (if (= :success (:status server-result))
            (swap! !state assoc
                   :inspection (:data server-result)
                   :candidate-value (:candidate-value (:data server-result))
                   :step :inspect)
            (swap! !state assoc
                   :error (or (:error server-result) "inspection failed")
                   :step :inspect)))))

     ;; Fire the apply mutation when the step flips to :applying.
     (when (= step :applying)
       (let [opts {:op :promote-to-global
                   :path (:path state)
                   :candidate-value (:candidate-value state)
                   :non-matching-strategy (:strategy state)
                   :changelog (:changelog state)
                   :user-id user-id}
             server-result (e/server
                            (e/Offload
                             #(try
                                {:status :success
                                 :result (common/mutate-config-tree! opts)}
                                (catch #?(:clj Exception :cljs :default) e
                                  {:status :error
                                   :error (or (ex-message e) "apply failed")}))))]
         (e/client
          (case (:status server-result)
            :success (swap! !state assoc
                            :step :done
                            :result (:result server-result))
            (swap! !state assoc
                   :step :preview
                   :error (:error server-result))))))

     (dom/div
      (dom/props {:style modal-backdrop-style})
      (dom/div
       (dom/props {:style modal-panel-style})
       (case step
         :loading (dom/div (dom/text "Loading inspection..."))
         :inspect (InspectStep !state)
         :preserve (PreserveStep !state)
         :preview (PreviewStep !state)
         :applying (dom/div (dom/text "Applying promotion..."))
         :done (DoneStep !state)
         (dom/text ""))
       (WizardButtons !state On-close On-applied))))))
