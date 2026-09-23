(ns digdir.config.ui.agents
  "Agents panel: what the config DB holds, and how it differs from code."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            #?(:clj [clojure.edn :as edn])
            [clojure.string :as str]
            #?(:clj [digdir.agents.core :as agents])
            #?(:clj [digdir.skills.api :as skills-api])
            #?(:clj [digdir.skills.init :as skills-init])
            #?(:clj [digdir.agents.db :as agents-db])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.config.permissions :as perms])
            #?(:clj [digdir.config.ui.common :as common])))

#?(:clj
   (defn reseed-agent!
     "Rewrite one agent from its code definition. Admin only."
     [user-id agent-id]
     (let [conn (config-db/get-conn)]
       (common/ensure-config-ui-admin! @conn user-id)
       (agents-db/seed-agent! conn agent-id)
       true)))

#?(:clj
   (defn available-graphs
     []
     (vec (sort (map str (agents/available-skill-graph-ids))))))

#?(:clj
   (defn save-agent!
     "Create or update an agent. Admin only. Returns {:ok id} or {:error msg}.
      skill-params arrives as EDN text, so parsing it is part of validation."
     [user-id agent skill-params-edn]
     (let [conn (config-db/get-conn)]
       (common/ensure-config-ui-admin! @conn user-id)
       (try
         (let [params (if (str/blank? skill-params-edn) {} (edn/read-string skill-params-edn))]
           (when-not (map? params)
             (throw (ex-info "Skill params must be a map." {})))
           {:ok (:id (agents-db/upsert-agent! conn (assoc agent :skill-params params)))})
         (catch Exception e
           {:error (or (ex-message e) "Could not save the agent.")})))))

#?(:clj
   (defn skill-param-catalogue
     "Registered skills and the parameters they declare."
     []
     (skills-init/ensure-initialized!)
     (->> (skills-api/list-skills)
          (keep (fn [sk]
                  (when (seq (:parameters sk))
                    {:skill (str (:skill-id sk))
                     :params (mapv (comp str symbol key) (sort-by key (:parameters sk)))})))
          (sort-by :skill)
          vec)))

#?(:clj
   (defn merge-skill-param
     "Merge one parameter into EDN params text. Returns {:ok text} or {:error msg}."
     [edn-text skill param value]
     (try
       (let [m (if (str/blank? edn-text) {} (edn/read-string edn-text))
             v (try (edn/read-string value) (catch Exception _ value))]
         {:ok (pr-str (assoc-in m [(keyword (subs skill 1)) (keyword param)] v))})
       (catch Exception e
         {:error (str "Could not merge: " (ex-message e))}))))

#?(:clj
   (defn delete-agent!
     "Delete a custom agent. Admin only, and refuses one defined in code."
     [user-id agent-id]
     (let [conn (config-db/get-conn)]
       (common/ensure-config-ui-admin! @conn user-id)
       (if (some #(= agent-id (:id %)) (agents/builtin-agent-definitions))
         {:error "Defined in code; the next restart would recreate it."}
         (do (agents-db/delete-agent! conn agent-id)
             {:ok agent-id})))))

#?(:clj
   (defn reseed-all-agents!
     "Rewrite every builtin agent from its code definitions. Admin only."
     [user-id]
     (let [conn (config-db/get-conn)]
       (common/ensure-config-ui-admin! @conn user-id)
       (count (agents-db/seed-builtin-agents! conn)))))

(def ^:private status-styles
  {:matches-code {:bg "#f0fdf4" :fg "#166534" :border "#bbf7d0" :label "matches code"}
   :stale        {:bg "#fffbeb" :fg "#92400e" :border "#fde68a" :label "stale"}
   :narrowed     {:bg "#fef2f2" :fg "#991b1b" :border "#fecaca" :label "narrowed"}
   :diverged     {:bg "#eff6ff" :fg "#1e40af" :border "#bfdbfe" :label "diverged"}
   :custom       {:bg "#f9fafb" :fg "#4b5563" :border "#e5e7eb" :label "custom"}
   :unseeded     {:bg "#fffbeb" :fg "#92400e" :border "#fde68a" :label "unseeded"}})

(def ^:private explanations
  {:matches-code "A reseed would change nothing."
   :stale        "Declared graphs are missing from this row but ARE registered here. A reseed restores them."
   :narrowed     "Declared graphs are missing and are NOT registered here. A reseed cannot restore them; the registration is the problem."
   :diverged     "This row differs from the code definition. A reseed would revert it."
   :custom       "No code definition. Seeding ignores this agent."
   :unseeded     "Declared in code but not stored. A reseed would create it."})

(def ^:private cell-style
  {:padding "0.5rem 0.75rem"
   :border-bottom "1px solid #e5e7eb"
   :font-size "0.875rem"
   :vertical-align "top"})

(def ^:private header-style
  (assoc cell-style :font-weight "600" :text-align "left"
         :color "#374151" :background "#f9fafb"))

(def ^:private mono {:font-family "ui-monospace, monospace" :font-size "0.8125rem"})

(e/defn StatusBadge [status]
  (e/client
   (let [s (get status-styles status (:custom status-styles))]
     (dom/span
      (dom/props {:style {:display "inline-block" :padding "0.125rem 0.5rem"
                          :border-radius "9999px" :font-size "0.75rem"
                          :font-weight "500" :white-space "nowrap"
                          :background (:bg s) :color (:fg s)
                          :border (str "1px solid " (:border s))}})
      (dom/text (:label s))))))

(e/defn GraphLines [graphs colour prefix]
  (e/client
   (e/for [g (e/diff-by identity graphs)]
     (dom/div
      (dom/props {:style (assoc mono :color colour)})
      (dom/text (str prefix g))))))

(def ^:private reseedable
  "Statuses a reseed can change. Not :narrowed, whose graphs are unregistered."
  #{:stale :diverged :unseeded})

(e/defn ReseedButton [row is-admin user-id]
  (e/client
   (let [status (:status row)
         blocked (cond
                   (not is-admin) "Requires the admin-full permission."
                   (= status :narrowed) (str "The missing graphs are not registered on this "
                                             "instance, so a reseed would report success and "
                                             "change nothing.")
                   (not (contains? reseedable status)) "Nothing for a reseed to change."
                   :else nil)]
     (dom/div
      (dom/props {:style {:margin-top "0.5rem"}})
      (ks/Button (cond-> {:data-size "sm" :data-variant "tertiary"}
                   blocked (assoc :disabled true :title blocked))
                 (e/fn []
                   (dom/text "Reseed")
                   (let [[tok _] (e/Token (dom/On "click" identity nil))]
                     (when tok
                       (case (and (nil? blocked)
                                  (e/client
                                   (js/confirm
                                    (str "Rewrite " (:id row) " from its code definition?\n\n"
                                         "This overwrites name, description, instructions, "
                                         "guardrails, enabled and allowed graphs on this agent.")))
                                  (e/server (reseed-agent! user-id (:id row))))
                         (tok))))))))))

(e/defn AgentRow [row is-admin user-id !editing]
  (e/client
   (let [stored (:stored row)
         declared (:declared row)
         status (:status row)
         graphs (:allowed-skill-graphs row)
         differing (:differing-fields row)]
     (dom/tr
      (dom/td (dom/props {:style (merge cell-style mono)}) (dom/text (:id row)))
      (dom/td (dom/props {:style cell-style})
              (dom/text (or (:name stored) (:name declared) "")))
      (dom/td (dom/props {:style cell-style})
              (dom/text (if (some? stored) (if (:enabled? stored) "yes" "no") "—")))
      (dom/td (dom/props {:style cell-style}) (StatusBadge status))
      (dom/td
       (dom/props {:style cell-style})
       (GraphLines (or (:stored graphs) (:declared graphs) []) "#111827" "")
       (GraphLines (:recoverable graphs) "#92400e" "+ ")
       (GraphLines (:unavailable graphs) "#991b1b" "unavailable: ")
       (when (seq differing)
         (dom/div
          (dom/props {:style {:font-size "0.75rem" :color "#1e40af" :margin-top "0.25rem"}})
          (dom/text (str "differs: " (str/join ", " (map name differing))))))
       (when (not= status :matches-code)
         (dom/div
          (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :margin-top "0.25rem"}})
          (dom/text (get explanations status ""))))
       (ReseedButton row is-admin user-id)
       (dom/div
        (dom/props {:style {:display "flex" :gap "0.5rem" :margin-top "0.25rem"}})
        (ks/Button (cond-> {:data-size "sm" :data-variant "tertiary"}
                     (not is-admin) (assoc :disabled true :title "Requires the admin-full permission."))
                   (e/fn []
                     (dom/text "Duplicate")
                     (let [[tok _] (e/Token (dom/On "click" identity nil))]
                       (when tok
                         (case (reset! !editing {:mode :duplicate :row row}) (tok))))))
        (ks/Button (cond-> {:data-size "sm" :data-variant "tertiary"}
                     (or (not is-admin) (nil? (:stored row)))
                     (assoc :disabled true
                            :title (if is-admin
                                     "Nothing stored yet for this agent."
                                     "Requires the admin-full permission.")))
                   (e/fn []
                     (dom/text "Edit")
                     (let [[tok _] (e/Token (dom/On "click" identity nil))]
                       (when tok
                         (case (reset! !editing {:mode :edit :row row}) (tok))))))
        (ks/Button (cond-> {:data-size "sm" :data-variant "tertiary" :data-color "danger"}
                     (or (not is-admin) (not= :custom (:status row)))
                     (assoc :disabled true
                            :title (if is-admin
                                     "Defined in code; the next restart would recreate it."
                                     "Requires the admin-full permission.")))
                   (e/fn []
                     (dom/text "Delete")
                     (let [[tok _] (e/Token (dom/On "click" identity nil))]
                       (when tok
                         (case (and (e/client
                                     (js/confirm
                                      (str "Delete " (:id row) "?\n\n"
                                           "Conversations that used this agent keep its id and "
                                           "will no longer resolve to an agent. This cannot be "
                                           "undone.")))
                                    (e/server (delete-agent! user-id (:id row))))
                           (tok))))))))))))

(e/defn Field [label value on-input & [{:keys [disabled placeholder multiline]}]]
  (e/client
   (dom/div
    (dom/props {:style {:margin-bottom "0.75rem"}})
    (dom/label
     (dom/props {:style {:display "block" :font-size "0.8125rem" :font-weight "500"
                         :margin-bottom "0.25rem" :color "#374151"}})
     (dom/text label))
    (if multiline
      (dom/textarea
       (dom/props {:value value :rows 3 :placeholder (or placeholder "")
                   :style {:width "100%" :padding "0.5rem" :border "1px solid #d1d5db"
                           :border-radius "4px" :font-size "0.875rem"}})
       (dom/On "input" #(on-input (.. % -target -value)) nil))
      (dom/input
       (dom/props (cond-> {:type "text" :value value :placeholder (or placeholder "")
                           :style {:width "100%" :padding "0.5rem" :border "1px solid #d1d5db"
                                   :border-radius "4px" :font-size "0.875rem"}}
                    disabled (assoc :disabled true)))
       (dom/On "input" #(on-input (.. % -target -value)) nil))))))

(e/defn Select [label value options on-change]
  (e/client
   (dom/div
    (dom/props {:style {:margin-bottom "0.75rem"}})
    (dom/label
     (dom/props {:style {:display "block" :font-size "0.8125rem" :font-weight "500"
                         :margin-bottom "0.25rem" :color "#374151"}})
     (dom/text label))
    ;; The selected option is rendered first and the placeholder dropped, because
    ;; a select shows its first option and Electric mounts options after any
    ;; :value or :selected we set.
    (let [chosen (when-not (str/blank? value) value)
          ordered (cond-> (vec (remove #(= % chosen) options))
                    chosen (->> (into [chosen]))
                    (nil? chosen) (->> (into [""])))]
      (dom/select
       (dom/props {:style {:width "100%" :padding "0.5rem" :border "1px solid #d1d5db"
                           :border-radius "4px" :font-size "0.875rem" :background "#fff"}})
       (dom/On "change" #(on-change (.. % -target -value)) nil)
       (e/for [o (e/diff-by identity ordered)]
         (dom/option (dom/props {:value o}) (dom/text (if (= o "") "—" o)))))))))

(e/defn AgentForm [editing is-admin user-id graphs catalogue !editing]
  (e/client
   (let [row (:row editing)
         mode (:mode editing)
         stored (:stored row)
         seed (case mode
                :create {}
                :duplicate (assoc stored :id "" :name (str (:name stored) " (copy)"))
                (or stored {}))
         !id (atom (or (:id seed) ""))
         !name (atom (or (:name seed) ""))
         !desc (atom (or (:description seed) ""))
         !instr (atom (or (:instructions seed) ""))
         !params (atom (if (seq (:skill-params seed)) (pr-str (:skill-params seed)) ""))
         !allowed (atom (set (:allowed-skill-graphs seed)))
         !default (atom (or (:default-skill-graph seed) ""))
         !enabled (atom (if (contains? seed :enabled?) (:enabled? seed) true))
         !err (atom nil)
         !pick-skill (atom "") !pick-param (atom "") !pick-value (atom "")
         id-v (e/watch !id) name-v (e/watch !name) desc-v (e/watch !desc)
         instr-v (e/watch !instr) params-v (e/watch !params) allowed-v (e/watch !allowed)
         default-v (e/watch !default) enabled-v (e/watch !enabled) err (e/watch !err)
         pick-skill (e/watch !pick-skill) pick-param (e/watch !pick-param)
         pick-value (e/watch !pick-value)
         builtin? (and (= mode :edit) (not= :custom (:status row)))
         params-for (fn [sk] (or (some #(when (= sk (:skill %)) (:params %)) catalogue) []))]
     (dom/div
      (dom/props {:style {:position "fixed" :top "0" :left "0" :right "0" :bottom "0"
                          :background "rgba(0,0,0,0.5)" :display "flex"
                          :align-items "flex-start" :justify-content "center"
                          :overflow-y "auto" :padding "2rem 1rem" :z-index "1000"}})
      (dom/div
       (dom/props {:style {:background "#fff" :border-radius "8px" :padding "1.5rem"
                           :width "min(640px, 100%)" :max-height "none"}})
       (dom/div
        (dom/props {:style {:font-weight "600" :margin-bottom "0.75rem"}})
        (dom/text (case mode :create "New agent"
                             :duplicate (str "Duplicate " (:id stored))
                             (str "Edit " (:id stored)))))
       (when builtin?
         (dom/div
          (dom/props {:style {:background "#fffbeb" :border "1px solid #fde68a" :color "#92400e"
                              :padding "0.5rem 0.75rem" :border-radius "6px"
                              :font-size "0.8125rem" :margin-bottom "0.75rem"}})
          (dom/text (str "This agent is defined in code. Allowed graphs and the default are "
                         "reconciled from code at every restart, so changes to those will not "
                         "stick. Name, description and skill params persist. Duplicating gives "
                         "you a copy nothing reconciles."))))
       (Field "ID" id-v #(reset! !id %) {:disabled (= mode :edit)
                                         :placeholder "tenant/agent-name"})
       (Field "Name" name-v #(reset! !name %))
       (Field "Description" desc-v #(reset! !desc %))
       (Field "Instructions (optional)" instr-v #(reset! !instr %)
              {:multiline true :placeholder "Stored for humans; the runtime does not read it."})
       (dom/div
        (dom/props {:style {:margin-bottom "0.75rem"}})
        (dom/label
         (dom/props {:style {:display "block" :font-size "0.8125rem" :font-weight "500"
                             :margin-bottom "0.25rem" :color "#374151"}})
         (dom/text "Allowed skill graphs"))
        (e/for [g (e/diff-by identity graphs)]
          (dom/label
           (dom/props {:style {:display "flex" :align-items "center" :gap "0.5rem"
                               :font-size "0.8125rem" :cursor "pointer"}})
           (dom/input
            (dom/props {:type "checkbox" :checked (contains? allowed-v g)})
            (dom/On "change"
                    (fn [_] (swap! !allowed #(if (contains? % g) (disj % g) (conj % g)))) nil))
           (dom/text g))))
       (Select "Default skill graph" default-v (vec (sort allowed-v)) #(reset! !default %))
       (dom/div
        (dom/props {:style {:border "1px solid #e5e7eb" :border-radius "6px"
                            :padding "0.75rem" :margin-bottom "0.75rem"}})
        (dom/div
         (dom/props {:style {:font-size "0.8125rem" :font-weight "500" :margin-bottom "0.5rem"
                             :color "#374151"}})
         (dom/text "Skill params"))
        (Select "Skill" pick-skill (mapv :skill catalogue)
                (fn [v] (reset! !pick-skill v) (reset! !pick-param "")))
        (Select "Parameter" pick-param (params-for pick-skill) #(reset! !pick-param %))
        (Field "Value" pick-value #(reset! !pick-value %) {:placeholder "e.g. 5, 0.7, \"phrase\""})
        (ks/Button {:data-size "sm" :data-variant "tertiary"}
                   (e/fn []
                     (dom/text "Add parameter")
                     (let [[tok _] (e/Token (dom/On "click" identity nil))]
                       (when tok
                         (case (let [res (e/server (merge-skill-param params-v pick-skill
                                                                      pick-param pick-value))]
                                 (if (:error res)
                                   (reset! !err (:error res))
                                   (do (reset! !params (:ok res)) (reset! !pick-value ""))))
                           (tok))))))
        (Field "EDN" params-v #(reset! !params %)
               {:multiline true
                :placeholder "{:builtin/retrieval {:retrieve-top-k 150}}"}))
       (dom/label
        (dom/props {:style {:display "flex" :align-items "center" :gap "0.5rem"
                            :font-size "0.8125rem" :margin-bottom "0.75rem" :cursor "pointer"}})
        (dom/input
         (dom/props {:type "checkbox" :checked enabled-v})
         (dom/On "change" (fn [_] (swap! !enabled not)) nil))
        (dom/text "Enabled"))
       (when err
         (dom/div
          (dom/props {:style {:color "#991b1b" :font-size "0.8125rem" :margin-bottom "0.75rem"}})
          (dom/text err)))
       (dom/div
        (dom/props {:style {:display "flex" :gap "0.5rem"}})
        (ks/Button {:data-size "sm" :data-variant "primary"}
                   (e/fn []
                     (dom/text "Save")
                     (let [[tok _] (e/Token (dom/On "click" identity nil))]
                       (when tok
                         (case (let [res (e/server
                                          (save-agent! user-id
                                                       {:id id-v :name name-v :description desc-v
                                                        :instructions instr-v
                                                        :allowed-skill-graphs (vec allowed-v)
                                                        :default-skill-graph default-v
                                                        :enabled? enabled-v}
                                                       params-v))]
                                 (if (:error res)
                                   (reset! !err (:error res))
                                   (reset! !editing nil)))
                           (tok))))))
        (ks/Button {:data-size "sm" :data-variant "tertiary"}
                   (e/fn []
                     (dom/text "Cancel")
                     (let [[tok _] (e/Token (dom/On "click" identity nil))]
                       (when tok
                         (case (reset! !editing nil) (tok))))))))))))

(e/defn AgentsUI []
  (e/client
   (let [!editing (atom nil)
         editing (e/watch !editing)
         graphs (e/server (available-graphs))
         catalogue (e/server (skill-param-catalogue))
         user-id (e/server (:user/id e/http-request))
         ;; e/watch, not deref: the table must redraw after a reseed writes.
         rows (e/server (if-let [conn (config-db/get-conn)]
                          (agents-db/drift-report (e/watch conn))
                          []))
         is-admin (e/server (let [conn (config-db/get-conn)]
                              (boolean (and conn (perms/is-admin? @conn user-id)))))
         needing (count (remove #(#{:matches-code :custom} (:status %)) rows))]
     (dom/div
      (dom/props {:style {:padding "1rem" :max-width "100%"}})
      (dom/div
       (dom/props {:style {:font-size "1rem" :font-weight "600" :margin-bottom "0.25rem"}})
       (dom/text "Agents"))
      (dom/div
       (dom/props {:style {:font-size "0.875rem" :color "#6b7280" :margin-bottom "1rem"}})
       (dom/text (str (count rows) " agent(s). "
                      (if (zero? needing)
                        "All stored rows agree with the code definitions."
                        (str needing " differ from the code definitions."))
                      " An agent can only run a skill graph listed on its row, "
                      "regardless of what the code declares or an API key grants.")))
      (e/for [f (e/diff-by :mode (if editing [editing] []))]
        (AgentForm f is-admin user-id graphs catalogue !editing))
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem" :margin-bottom "1rem"}})
       (ks/Button (cond-> {:data-size "sm" :data-variant "primary"}
                    (not is-admin) (assoc :disabled true :title "Requires the admin-full permission."))
                  (e/fn []
                    (dom/text "New agent")
                    (let [[tok _] (e/Token (dom/On "click" identity nil))]
                      (when tok
                        (case (reset! !editing {:mode :create}) (tok))))))
       (ks/Button (cond-> {:data-size "sm" :data-variant "secondary"}
                    (or (not is-admin) (zero? needing))
                    (assoc :disabled true
                           :title (if is-admin
                                    "Every agent already agrees with its code definition."
                                    "Requires the admin-full permission.")))
                  (e/fn []
                    (dom/text "Reseed all from code")
                    (let [[tok _] (e/Token (dom/On "click" identity nil))]
                      (when tok
                        (case (and is-admin (pos? needing)
                                   (e/client
                                    (js/confirm
                                     (str "Rewrite all builtin agents from their code definitions?\n\n"
                                          "This overwrites name, description, instructions, guardrails, "
                                          "enabled and allowed graphs on every agent that has a code "
                                          "definition, not only the ones listed as differing.")))
                                   (e/server (reseed-all-agents! user-id)))
                          (tok)))))))
      (if (empty? rows)
        (dom/div (dom/props {:style {:font-size "0.875rem" :color "#6b7280"}})
                 (dom/text "No agents stored or declared."))
        (dom/table
         (dom/props {:style {:border-collapse "collapse" :width "100%"}})
         (dom/thead
          (dom/tr
           (dom/th (dom/props {:style header-style}) (dom/text "ID"))
           (dom/th (dom/props {:style header-style}) (dom/text "Name"))
           (dom/th (dom/props {:style header-style}) (dom/text "Enabled"))
           (dom/th (dom/props {:style header-style}) (dom/text "Status"))
           (dom/th (dom/props {:style header-style}) (dom/text "Allowed graphs"))))
         (dom/tbody
          (e/for [row (e/diff-by :id rows)]
            (AgentRow row is-admin user-id !editing)))))))))
