(ns digdir.config.ui.permissions
  "Permission management UI for configuration access control."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            #?(:clj [clojure.edn :as edn])
            #?(:clj [digdir.config.permissions :as perms])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [datahike.api :as d])))

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

(def badge-style
  {:padding "0.125rem 0.5rem"
   :border-radius "9999px"
   :font-size "0.75rem"
   :font-weight "500"
   :margin-right "0.25rem"
   :margin-bottom "0.25rem"
   :display "inline-block"})

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

#?(:clj
   (defn parse-edn-str [s]
     (try
       (when (and s (seq s))
         (edn/read-string s))
       (catch Exception _ nil))))

(defn format-attr-value
  "Format an attribute value for display."
  [v]
  (cond
    (= v :*) "*"
    (set? v) (str/join ", " (map name v))
    (keyword? v) (name v)
    :else (str v)))

;; =============================================================================
;; Server-side Data Functions
;; =============================================================================

#?(:clj
   (defn get-permissions-data
     "Get all permissions and users with permissions."
     []
     (when-let [conn (config-db/get-conn)]
       (let [db @conn
             all-perms (perms/get-all-permissions db)]
         {:permissions
          (map (fn [perm]
                 (assoc perm
                        :parsed-attributes (parse-edn-str (:permission/attributes perm))
                        :parsed-tenants (parse-edn-str (:permission/tenants perm))
                        :parsed-tenant-config-keys (parse-edn-str (:permission/tenant-config-keys perm))
                        :parsed-actions (parse-edn-str (:permission/actions perm))
                        :user-count (count (perms/get-users-with-permission db (:permission/id perm)))
                        :formatted-created (format-timestamp (:permission/created-at perm))))
               all-perms)}))))

#?(:clj
   (defn get-permission-users
     "Get users who have a specific permission."
     [permission-id]
     (when-let [conn (config-db/get-conn)]
       (perms/get-users-with-permission @conn permission-id))))

#?(:clj
   (defn get-all-users
     "Get all users in the system."
     []
     (when-let [conn (config-db/get-conn)]
       (d/q '[:find [(pull ?e [:user/id :user/email]) ...]
              :where [?e :user/id]]
            @conn))))

#?(:clj
   (defn get-user-permissions-data
     "Get a user's permissions."
     [user-id]
     (when-let [conn (config-db/get-conn)]
       (perms/get-user-permissions @conn user-id))))

#?(:clj
   (defn grant-permission-to-user!
     "Grant a permission to a user."
     [user-id permission-id]
     (when-let [conn (config-db/get-conn)]
       (perms/grant-permission! conn user-id permission-id)
       :ok)))

#?(:clj
   (defn revoke-permission-from-user!
     "Revoke a permission from a user."
     [user-id permission-id]
     (when-let [conn (config-db/get-conn)]
       (perms/revoke-permission! conn user-id permission-id)
       :ok)))

;; =============================================================================
;; UI Components
;; =============================================================================

(e/defn AttrBadge [label value color]
  (dom/span
   (dom/props {:style (merge badge-style {:background color :color "#374151"})})
   (dom/text (str label ": " (format-attr-value value)))))

(e/defn ActionBadge [action]
  (let [color (case action
                :read "#dbeafe"
                :write "#dcfce7"
                "#e5e7eb")]
    (dom/span
     (dom/props {:style (merge badge-style {:background color})})
     (dom/text (name action)))))

(e/defn PermissionCard [perm !selected-perm]
  (e/client
   (let [selected (e/watch !selected-perm)
         is-selected (= (:permission/id perm) selected)
         attrs (:parsed-attributes perm)
         tenants (:parsed-tenants perm)
         envs (:parsed-tenant-config-keys perm)
         actions (:parsed-actions perm)]
     (dom/div
      (dom/props {:style {:background (if is-selected "#eff6ff" "white")
                          :border (str "1px solid " (if is-selected "#3b82f6" "#e5e7eb"))
                          :border-radius "8px"
                          :padding "1rem"
                          :margin-bottom "0.75rem"
                          :cursor "pointer"}})
      (let [[t err] (e/Token (dom/On "click" identity nil))]
        (when t
          (reset! !selected-perm (if is-selected nil (:permission/id perm)))
          (t)))

      ;; Header
      (dom/div
       (dom/props {:style {:display "flex"
                           :justify-content "space-between"
                           :align-items "flex-start"
                           :margin-bottom "0.5rem"}})
       (dom/div
        (dom/div
         (dom/props {:style {:font-weight "600" :color "#111827"}})
         (dom/text (:permission/name perm)))
        (dom/div
         (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :font-family "monospace"}})
         (dom/text (:permission/id perm))))
       (dom/span
        (dom/props {:style {:font-size "0.75rem"
                            :background "#f3f4f6"
                            :padding "0.25rem 0.5rem"
                            :border-radius "9999px"
                            :color "#6b7280"}})
        (dom/text (str (:user-count perm) " users"))))

      ;; Description
      (when-let [desc (:permission/description perm)]
        (dom/div
         (dom/props {:style {:font-size "0.875rem" :color "#6b7280" :margin-bottom "0.5rem"}})
         (dom/text desc)))

      ;; Attributes
      (dom/div
       (dom/props {:style {:margin-top "0.5rem"}})
       (when attrs
         (dom/div
          (dom/props {:style {:margin-bottom "0.25rem"}})
          (AttrBadge "service" (:service attrs) "#fef3c7")
          (AttrBadge "sensitivity" (:sensitivity attrs) "#fee2e2")
          (AttrBadge "function" (:function attrs) "#dbeafe")))

       (dom/div
        (dom/props {:style {:margin-bottom "0.25rem"}})
        (AttrBadge "tenants" tenants "#e0e7ff")
        (AttrBadge "tenant-config-keys" envs "#d1fae5"))

       (when actions
         (dom/div
          (e/for [action (e/diff-by identity (if (set? actions) actions #{actions}))]
            (ActionBadge action)))))))))

(e/defn PermissionUserList [permission-id !refresh]
  (e/client
   (let [refresh (e/watch !refresh)
         _ refresh
         users (e/server (get-permission-users permission-id))]
     (if (empty? users)
       (dom/div
        (dom/props {:style {:color "#6b7280" :font-size "0.875rem"}})
        (dom/text "No users have this permission."))
       (dom/div
        (e/for [user (e/diff-by :user/id users)]
          (dom/div
           (dom/props {:style {:display "flex"
                               :justify-content "space-between"
                               :align-items "center"
                               :padding "0.5rem"
                               :background "#f9fafb"
                               :border-radius "4px"
                               :margin-bottom "0.25rem"}})
           (dom/span (dom/text (:user/email user)))
           (dom/button
            (dom/props {:style {:padding "0.25rem 0.5rem"
                                :background "#fee2e2"
                                :color "#991b1b"
                                :border "none"
                                :border-radius "4px"
                                :cursor "pointer"
                                :font-size "0.75rem"}})
            (dom/text "Revoke")
            (let [[t err] (e/Token (dom/On "click" identity nil))]
              (when t
                (e/server (revoke-permission-from-user! (:user/id user) permission-id))
                (swap! !refresh inc)
                (t)))))))))))

(e/defn AddUserToPermission [permission-id !refresh]
  (e/client
   (let [!selected-user (atom nil)
         selected-user (e/watch !selected-user)
         all-users (e/server (get-all-users))
         existing-users (e/server (set (map :user/id (get-permission-users permission-id))))
         available-users (filter #(not (contains? existing-users (:user/id %))) all-users)]
     (dom/div
      (dom/props {:style {:display "flex"
                          :gap "0.5rem"
                          :margin-top "1rem"
                          :padding-top "1rem"
                          :border-top "1px solid #e5e7eb"}})
      (dom/select
       (dom/props {:style {:flex "1"
                           :padding "0.5rem"
                           :border "1px solid #d1d5db"
                           :border-radius "4px"}
                   :value (or selected-user "")})
       (dom/option (dom/props {:value ""}) (dom/text "Select user to add..."))
       (e/for [user (e/diff-by :user/id available-users)]
         (dom/option
          (dom/props {:value (:user/id user)})
          (dom/text (:user/email user))))
       (dom/On "change" #(reset! !selected-user (let [v (.. % -target -value)]
                                                   (when (seq v) v))) nil))
      (dom/button
       (dom/props {:style {:padding "0.5rem 1rem"
                           :background (if selected-user "#3b82f6" "#e5e7eb")
                           :color (if selected-user "white" "#9ca3af")
                           :border "none"
                           :border-radius "4px"
                           :cursor (if selected-user "pointer" "not-allowed")}})
       (dom/text "Add")
       (let [[t err] (e/Token (dom/On "click" identity nil))]
         (when (and t selected-user)
           (e/server (grant-permission-to-user! selected-user permission-id))
           (reset! !selected-user nil)
           (swap! !refresh inc)
           (t))))))))

(e/defn PermissionDetails [permission-id !refresh-outer]
  (e/client
   (let [!refresh (atom 0)
         refresh (e/watch !refresh)]
     (dom/div
      (dom/props {:style {:background "white"
                          :border "1px solid #e5e7eb"
                          :border-radius "8px"
                          :padding "1rem"}})
      (dom/div
       (dom/props {:style {:font-weight "600"
                           :margin-bottom "1rem"
                           :padding-bottom "0.5rem"
                           :border-bottom "1px solid #e5e7eb"}})
       (dom/text "Users with this permission"))

      (PermissionUserList permission-id !refresh)
      (AddUserToPermission permission-id !refresh)))))

;; =============================================================================
;; Main Permissions Component
;; =============================================================================

(e/defn PermissionsManagement []
  (e/client
   (let [!refresh (atom 0)
         !selected-perm (atom nil)
         refresh (e/watch !refresh)
         selected-perm (e/watch !selected-perm)

         _ refresh
         perms-data (e/server (get-permissions-data))
         permissions (sort-by :permission/name (:permissions perms-data))]

     (dom/div
      (dom/props {:style {:padding "1rem" :max-width "1400px"}})

      ;; Header
      (dom/div
       (dom/props {:style {:display "flex"
                           :justify-content "space-between"
                           :align-items "center"
                           :margin-bottom "1rem"}})
       (ks/Heading {:level 2} (e/fn [] (dom/text "Permissions Management")))
       (dom/button
        (dom/props {:style {:padding "0.5rem 1rem"
                            :background "#3b82f6"
                            :color "white"
                            :border "none"
                            :border-radius "4px"
                            :cursor "pointer"}})
        (dom/text "Refresh")
        (let [[t err] (e/Token (dom/On "click" identity nil))]
          (when t
            (swap! !refresh inc)
            (t)))))

      ;; Description
      (dom/div
       (dom/props {:style {:margin-bottom "1rem"
                           :padding "1rem"
                           :background "#f9fafb"
                           :border-radius "8px"
                           :font-size "0.875rem"
                           :color "#6b7280"}})
       (dom/text "Permissions control access to configuration values based on attributes. ")
       (dom/text "Select a permission to view and manage assigned users."))

      ;; Two-column layout
      (dom/div
       (dom/props {:style {:display "grid"
                           :grid-template-columns (if selected-perm "1fr 1fr" "1fr")
                           :gap "1rem"}})

       ;; Permissions list
       (dom/div
        (dom/div
         (dom/props {:style {:font-weight "600"
                             :margin-bottom "0.75rem"
                             :color "#374151"}})
         (dom/text (str (count permissions) " Permissions")))
        (if (empty? permissions)
          (dom/div
           (dom/props {:style {:padding "2rem"
                               :text-align "center"
                               :color "#6b7280"}})
           (dom/text "No permissions defined. Run init-config-db! to seed defaults."))
          (e/for [perm (e/diff-by :permission/id permissions)]
            (PermissionCard perm !selected-perm))))

       ;; Selected permission details
       (when selected-perm
         (PermissionDetails selected-perm !refresh)))))))

(e/defn Permissions []
  (PermissionsManagement))
