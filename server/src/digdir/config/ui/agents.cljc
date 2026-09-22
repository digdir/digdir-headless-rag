(ns digdir.config.ui.agents
  "Agents panel: what the config DB holds, and how it differs from code."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
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

(e/defn AgentRow [row is-admin user-id]
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
       (ReseedButton row is-admin user-id))))))

(e/defn AgentsUI []
  (e/client
   (let [user-id (e/server (:user/id e/http-request))
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
      (dom/div
       (dom/props {:style {:margin-bottom "1rem"}})
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
            (AgentRow row is-admin user-id)))))))))
