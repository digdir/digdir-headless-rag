(ns digdir.config.ui.global
  "Global Defaults editor. A per-tenantless view of all inherit-owned config
   definitions with their current global value. Edits here bump the monotonic
   :config.global/version and emit :global-edit audit entries."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [clojure.string :as str]
            [digdir.config.ui.common :as common]
            [digdir.config.ui.inheritance :refer [OwnershipBadge]]
            [digdir.config.ui.ownership-migration :refer [PromoteToGlobalWizard]]
            [digdir.config.ui.styles :as styles]))

(def ^:private button-style styles/button-style)
(def ^:private input-style styles/input-style)
(def ^:private textarea-style styles/textarea-style)
(def ^:private diagnostics-label-style styles/diagnostics-label-style)

(def ^:private section-style
  {:padding "1rem"
   :border "1px solid #e5e7eb"
   :border-radius "8px"
   :background "white"
   :margin-bottom "1rem"})

(def ^:private table-style
  {:width "100%"
   :border-collapse "collapse"
   :font-size "0.875rem"})

(def ^:private th-style
  {:padding "0.5rem 0.75rem"
   :text-align "left"
   :background "#f9fafb"
   :border-bottom "1px solid #e5e7eb"
   :font-weight "600"
   :color "#374151"
   :font-size "0.75rem"
   :text-transform "uppercase"
   :letter-spacing "0.025em"})

(def ^:private td-style
  {:padding "0.6rem 0.75rem"
   :border-bottom "1px solid #f3f4f6"
   :vertical-align "top"})

(defn- initial-edit-raw-value
  [value value-type]
  (cond
    (nil? value) ""
    (= :string value-type) (str value)
    :else (pr-str value)))

(e/defn VersionBanner [version versions]
  (let [latest (first versions)]
    (dom/div
     (dom/props {:style (merge section-style
                               {:display "flex"
                                :justify-content "space-between"
                                :align-items "center"
                                :gap "1rem"
                                :flex-wrap "wrap"})})
     (dom/div
      (dom/div
       (dom/props {:style {:font-size "0.75rem"
                           :color "#6b7280"
                           :text-transform "uppercase"
                           :letter-spacing "0.04em"}})
       (dom/text "Current global version"))
      (dom/div
       (dom/props {:style {:font-size "1.75rem"
                           :font-weight "700"
                           :color "#1e3a8a"}})
       (dom/text (str "v" version))))
     (dom/div
      (dom/props {:style {:max-width "60%"}})
      (if latest
        (dom/div
         (dom/div
          (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
          (dom/text (str "Last change by "
                         (or (:config.global/created-by latest) "system")
                         " · "
                         (when-let [t (:config.global/created-at latest)]
                           (str t)))))
         (dom/div
          (dom/props {:style {:font-size "0.875rem" :color "#374151" :margin-top "0.15rem"}})
          (dom/text (str "v" (:config.global/version latest) " — "
                         (or (:config.global/changelog latest) "(no changelog)")))))
        (dom/div
         (dom/props {:style {:font-size "0.875rem" :color "#6b7280"}})
         (dom/text "No changes yet — the global layer has not been written to.")))))))

(e/defn GlobalEditModal
  [editing !editing !mutation]
  (e/client
   (let [{:keys [definition value draft-raw draft-changelog]} editing
         value-type (:config-def/value-type definition)
         raw-value (or draft-raw (initial-edit-raw-value value value-type))
         changelog-text (or draft-changelog "")
         path (:config-def/path definition)
         root (:config-def/root definition)]
     (dom/div
      (dom/props {:style {:position "fixed" :top "0" :left "0"
                          :width "100%" :height "100%"
                          :background "rgba(0,0,0,0.5)"
                          :display "flex" :align-items "center" :justify-content "center"
                          :z-index "100"}})
      (dom/div
       (dom/props {:style {:background "white"
                           :padding "1.5rem"
                           :border-radius "8px"
                           :width "460px"
                           :max-width "92%"}})
       (dom/div
        (dom/props {:style {:font-weight "600"
                            :margin-bottom "0.25rem"
                            :display "flex"
                            :align-items "center"
                            :gap "0.35rem"}})
        (dom/span (dom/text "Edit global"))
        (dom/span (dom/text path))
        (OwnershipBadge :inherit))
       (dom/div
        (dom/props {:style {:font-size "0.75rem"
                            :color "#6b7280"
                            :margin-bottom "0.75rem"}})
        (dom/text (str "Root: " (name root) " · Changes propagate to all tenants that aren't pinned.")))
       (dom/div
        (dom/label (dom/props {:style diagnostics-label-style}) (dom/text "Value"))
        (dom/textarea
         (dom/props {:style (merge textarea-style {:min-height "100px"
                                                    :width "100%"
                                                    :box-sizing "border-box"})
                     :value raw-value})
         (dom/On "input"
                 (fn [ev] (swap! !editing assoc :draft-raw (.. ev -target -value)))
                 nil)))
       (dom/div
        (dom/props {:style {:margin-top "0.75rem"}})
        (dom/label (dom/props {:style diagnostics-label-style})
                   (dom/text "Changelog (required)"))
        (dom/input
         (dom/props {:type "text"
                     :value changelog-text
                     :placeholder "Why this change? Who is affected?"
                     :style (merge input-style {:width "100%" :box-sizing "border-box"})})
         (dom/On "input"
                 (fn [ev] (swap! !editing assoc :draft-changelog (.. ev -target -value)))
                 nil)))
       (dom/div
        (dom/props {:style {:display "flex"
                            :gap "0.5rem"
                            :justify-content "flex-end"
                            :margin-top "1rem"}})
        (dom/button
         (dom/props {:style (button-style :secondary)})
         (dom/text "Cancel")
         (let [[tok _] (e/Token (dom/On "click" identity nil))]
           (when tok (reset! !editing nil) (tok))))
        (dom/button
         (dom/props {:style (button-style :primary)})
         (dom/text "Save global")
         (let [[tok _] (e/Token (dom/On "click" identity nil))]
           (when tok
             (reset! !mutation {:op :set-global-value
                                :root root
                                :path path
                                :raw-value raw-value
                                :changelog changelog-text})
             (tok))))))))))

(defn- group-by-category
  [definitions]
  (->> definitions
       (group-by (fn [d] (or (:config-def/category d) :uncategorized)))
       (sort-by (fn [[cat _]] (name cat)))
       vec))

(defn- display-value-for-definition
  [definition value-entity master-key]
  (when value-entity
    (common/decode-node-value value-entity definition master-key)))

(e/defn PromotionCandidatesSection [candidates On-promote]
  (when (seq candidates)
    (dom/div
     (dom/props {:style (merge section-style
                                {:border "1px solid #c7d2fe"
                                 :background "#eef2ff"})})
     (dom/div
      (dom/props {:style {:font-weight "600" :margin-bottom "0.4rem"
                          :display "flex" :align-items "center" :gap "0.4rem"}})
      (dom/span (dom/text "Suggested for promotion"))
      (dom/span (dom/props {:style {:font-size "0.7rem"
                                    :color "#4338ca"
                                    :background "white"
                                    :padding "1px 6px"
                                    :border-radius "9999px"
                                    :font-weight "600"}})
                (dom/text (str (count candidates)))))
     (dom/div
      (dom/props {:style {:font-size "0.75rem" :color "#4b5563"
                          :margin-bottom "0.6rem"}})
      (dom/text "Fork-owned definitions where ≥80% of tenants already share the same value — low-risk candidates for :inherit promotion."))
     (dom/table
      (dom/props {:style table-style})
      (dom/thead
       (dom/tr
        (dom/th (dom/props {:style th-style}) (dom/text "Path"))
        (dom/th (dom/props {:style (merge th-style {:width "90px"})}) (dom/text "Consensus"))
        (dom/th (dom/props {:style th-style}) (dom/text "Candidate value"))
        (dom/th (dom/props {:style (merge th-style {:width "110px"})}) (dom/text ""))))
      (dom/tbody
       (e/for [c (e/diff-by :path candidates)]
         (dom/tr
          (dom/td (dom/props {:style td-style})
                  (dom/div (dom/props {:style {:font-family "monospace"
                                               :font-weight "500"}})
                           (dom/text (:path c))))
          (dom/td (dom/props {:style (merge td-style {:color "#374151"})})
                  (dom/text (str (int (* 100 (:consensus-ratio c))) "% of " (:tenant-count c))))
          (dom/td (dom/props {:style td-style})
                  (dom/code (dom/props {:style {:font-size "0.8125rem"}})
                            (dom/text (common/truncate-value
                                       (pr-str (:candidate-value c)) 60))))
          (dom/td (dom/props {:style td-style})
                  (dom/button
                   (dom/props {:style (button-style :primary)})
                   (dom/text "Promote…")
                   (let [[tok _] (e/Token (dom/On "click" identity nil))]
                     (when tok (On-promote (:path c)) (tok))))))))))))

(e/defn GlobalDefaultsEditor [_user-id]
  (e/client
   (let [!state (atom :loading)
         !result (atom nil)
         !candidates-result (atom nil)
         !error (atom nil)
         !editing (atom nil)
         !wizard-def (atom nil)  ; full definition map when wizard is open
         !mutation (atom nil)
         !refresh (atom 0)
         state (e/watch !state)
         result (e/watch !result)
         candidates-result (e/watch !candidates-result)
         error (e/watch !error)
         editing (e/watch !editing)
         wizard-def (e/watch !wizard-def)
         mutation (e/watch !mutation)
         refresh-ticker (e/watch !refresh)]

     (when (= state :loading)
       (let [server-result (e/server
                            (let [_ (e/client refresh-ticker)]
                              (e/Offload #(try
                                            {:globals (common/get-global-defaults-data)
                                             :candidates (common/get-promotion-candidates)}
                                            (catch #?(:clj Exception :cljs :default) e
                                              {:status :error
                                               :error (or (ex-message e) "Failed to load")})))))]
         (e/client
          (cond
            (= :error (:status server-result))
            (do (reset! !error (or (:error server-result) "Failed to load"))
                (reset! !state :error))

            :else
            (let [{:keys [globals candidates]} server-result]
              (reset! !result (:data globals))
              (reset! !candidates-result (:data candidates))
              (reset! !error nil)
              (reset! !state :done))))))

     (when mutation
       (let [server-result (e/server
                            (e/Offload #(try
                                          (common/mutate-config-tree!
                                           (merge mutation {:user-id _user-id}))
                                          {:status :success}
                                          (catch #?(:clj Exception :cljs :default) e
                                            {:status :error
                                             :error (or (ex-message e) "Update failed")}))))]
         (e/client
          (if (= :success (:status server-result))
            (do (reset! !mutation nil)
                (reset! !editing nil)
                (swap! !refresh inc)
                (reset! !state :loading))
            (do (reset! !error (:error server-result))
                (reset! !mutation nil))))))

     (dom/div
      (dom/props {:style {:padding "1rem"}})
      (dom/h2
       (dom/props {:style {:font-size "1.25rem"
                           :font-weight "700"
                           :color "#111827"
                           :margin "0 0 0.5rem 0"}})
       (dom/text "Global Defaults"))
      (dom/div
       (dom/props {:style {:font-size "0.875rem" :color "#6b7280" :margin-bottom "1rem"}})
       (dom/text "System-wide defaults for inherit-owned definitions. Edits here bump the global version and propagate to every tenant that hasn't pinned."))

      (when error
        (dom/div
         (dom/props {:style (merge section-style
                                   {:background "#fef2f2"
                                    :border "1px solid #fecaca"
                                    :color "#b91c1c"})})
         (dom/text error)))

      (when (= state :loading)
        (dom/div (dom/text "Loading...")))

      (when (= state :done)
        (let [{:keys [definitions values-by-path version versions]} result
              master-key (e/server (common/get-master-key))
              groups (group-by-category definitions)
              candidates (:candidates candidates-result)
              candidate-defs-by-path (into {}
                                           (map (juxt :path identity))
                                           candidates)]
          (VersionBanner version versions)
          (PromotionCandidatesSection
           candidates
           (e/fn [path]
             ;; Fetch the full definition from server for this path and open the wizard.
             (let [def-from-candidate (get candidate-defs-by-path path)]
               (reset! !wizard-def
                       {:config-def/path path
                        :config-def/root nil
                        :candidate-value (:candidate-value def-from-candidate)}))))
          (if (empty? definitions)
            (dom/div
             (dom/props {:style section-style})
             (dom/text "No inherit-owned definitions are registered. Declare a definition with :ownership :inherit to see it here."))
            (e/for [[category group-defs] (e/diff-by first groups)]
              (dom/div
               (dom/props {:style section-style})
               (dom/div
                (dom/props {:style {:font-weight "600"
                                    :font-size "0.75rem"
                                    :text-transform "uppercase"
                                    :letter-spacing "0.04em"
                                    :color "#4b5563"
                                    :margin-bottom "0.5rem"}})
                (dom/text (name category)))
               (dom/table
                (dom/props {:style table-style})
                (dom/thead
                 (dom/tr
                  (dom/th (dom/props {:style th-style}) (dom/text "Path"))
                  (dom/th (dom/props {:style th-style}) (dom/text "Root"))
                  (dom/th (dom/props {:style th-style}) (dom/text "Current global value"))
                  (dom/th (dom/props {:style (merge th-style {:width "170px"})}) (dom/text ""))))
                (dom/tbody
                 (e/for [definition (e/diff-by :config-def/path group-defs)]
                   (let [path (:config-def/path definition)
                         value-entity (get values-by-path path)
                         decoded (when value-entity
                                   (e/server (display-value-for-definition
                                              definition value-entity master-key)))
                         has-value? (some? value-entity)]
                     (dom/tr
                      (dom/td (dom/props {:style td-style})
                              (dom/div (dom/props {:style {:font-family "monospace"
                                                           :font-weight "500"}})
                                       (dom/text path)))
                      (dom/td (dom/props {:style (merge td-style {:color "#6b7280"})})
                              (dom/text (name (:config-def/root definition))))
                      (dom/td (dom/props {:style td-style})
                              (if has-value?
                                (dom/code (dom/props {:style {:font-size "0.8125rem"}})
                                          (dom/text (common/truncate-value
                                                     (if (= :string (:config-def/value-type definition))
                                                       (str decoded)
                                                       (pr-str decoded))
                                                     80)))
                                (dom/span (dom/props {:style {:color "#9ca3af"}})
                                          (dom/text "— (no global value yet)"))))
                      (dom/td (dom/props {:style td-style})
                              (dom/div
                               (dom/props {:style {:display "flex"
                                                   :gap "0.375rem"
                                                   :justify-content "flex-end"}})
                               (dom/button
                                (dom/props {:style (button-style :secondary)})
                                (dom/text (if has-value? "Edit" "Set"))
                                (let [[tok _] (e/Token (dom/On "click" identity nil))]
                                  (when tok
                                    (reset! !editing {:definition definition
                                                      :value decoded
                                                      :draft-raw (initial-edit-raw-value
                                                                  decoded
                                                                  (:config-def/value-type definition))
                                                      :draft-changelog ""})
                                    (tok))))
                               (when has-value?
                                 (dom/button
                                  (dom/props {:style (button-style :danger :small)
                                              :title (str "Reverses promotion: copies the current global value into every tenant's "
                                                          "tree as a fork-owned value, deletes the global value, and flips "
                                                          "ownership back to :fork. Tenants that were already overriding keep "
                                                          "their local values; their pin-of-version stamps are dropped.")})
                                  (dom/text "Demote")
                                  (let [[tok _] (e/Token (dom/On "click" identity nil))]
                                    (when tok
                                      (when (js/confirm
                                             (str "Demote " path "?\n\n"
                                                  "This will push the current global value into every tenant "
                                                  "as a fork-owned value, delete the global value, and flip "
                                                  "ownership back to :fork."))
                                        (reset! !mutation {:op :demote-from-global
                                                           :path path}))
                                      (tok))))))))))))))))

      (when editing
        (GlobalEditModal editing !editing !mutation))

      (when wizard-def
        (PromoteToGlobalWizard
         wizard-def
         (e/fn [] (reset! !wizard-def nil))
         (e/fn []
           (reset! !wizard-def nil)
           (swap! !refresh inc)
           (reset! !state :loading))
         _user-id))))))
)