(ns digdir.config.ui.permissions
  "Permission management UI for configuration access control."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            #?(:clj [clojure.edn :as edn])
            #?(:clj [digdir.auth.core :as auth])
            #?(:clj [digdir.config.permissions :as perms])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.config.ui.common :as ui-common])
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
     "Get users who have a specific permission.

      `refresh-token` is unused on purpose: passing it makes this query re-read
      when the caller invalidates it. Keep the parameter (#587)."
     [permission-id _refresh-token]
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
     "Grant a permission to a user.

      `actor-id` is WHO IS ASKING, and it is not the same as `user-id`, who is
      the subject. Read it on the SERVER from `e/http-request` at the call site
      — never accept it from client scope, or the check is the caller's to
      forge."
     [user-id permission-id actor-id]
     (when-let [conn (config-db/get-conn)]
       (ui-common/ensure-config-ui-admin! @conn actor-id)
       (perms/grant-permission! conn user-id permission-id)
       :ok)))

#?(:clj
   (defn create-user-and-grant!
     "Create a user by email and grant them `permission-id`.

      ⚠️ DELEGATES; DOES NOT REIMPLEMENT. `auth/create-new-user` is the only
      thing in the system that brings a user into existence, and
      `perms/grant-permission!` is the only thing that grants. This composes the
      two in the same order `create-user-handler` (POST /console-api/users)
      already does, so the console and the HTTP endpoint share one mechanism
      rather than acquiring a second. Two callers of one mechanism is the defect
      class that has cost this repository most this month — see #541 and #550.

      ⚠️ AND IT REFUSES A DUPLICATE RATHER THAN SWALLOWING IT.
      `create-new-user` already returns `{:error \"User already exists\"}` for a
      taken address; the value of that refusal is entirely in whether the caller
      propagates it. Returning nil here — or granting anyway — would turn a
      refusal into a silent no-op, which is the shape that makes a broken
      control indistinguishable from a working one.

      ⚠️ AND IT REFUSES A NON-ADMIN CALLER, THROUGH THE SHARED GUARD. Bringing a
      user into existence and granting them a permission is the most privileged
      thing this panel does, and until #573's follow-up the panel was reachable
      by anyone holding ANY permission — so a read-only user could grant
      themselves admin-full. The guard THROWS rather than returning `{:error}`
      like the refusals above it: those describe a mistake the admin can correct
      in the form, whereas this one is not the admin's to correct, and a
      client that reaches it has bypassed the UI.

      `actor-id` is the caller, not the subject — see `grant-permission-to-user!`.

      Returns `{:ok true :user-id … :email …}` or `{:error message}`."
     [email permission-id actor-id]
     (if-let [conn (config-db/get-conn)]
       ;; AUTHORIZE BEFORE VALIDATING, deliberately. Checking the form first
       ;; would answer an unauthorized caller's questions about it — and it puts
       ;; the one check that must never be skipped ahead of every branch that
       ;; could return early past it.
       (do
         (ui-common/ensure-config-ui-admin! @conn actor-id)
         (let [email (some-> email str str/trim)]
           (cond
             (str/blank? email)
             {:error "Email is required."}

             (str/blank? (str permission-id))
             {:error "No permission selected."}

             :else
             (let [result (auth/create-new-user {:email email})]
               (if-let [err (:error result)]
                 ;; The duplicate case. Named rather than generic, because the
                 ;; admin's next step differs: an existing user is granted
                 ;; through the dropdown immediately above this control.
                 {:error err}
                 (if-let [user-id (:user/id (auth/user-by-email email))]
                   (do (perms/grant-permission! conn user-id permission-id)
                       {:ok true :user-id user-id :email email})
                   ;; Created but unreadable: report it rather than claiming
                   ;; success, so a half-done state cannot look finished.
                   {:error (str "User " email " was created but could not be read back.")}))))))
       {:error "No database connection."})))

#?(:clj
   (defn revoke-permission-from-user!
     "Revoke a permission from a user.

      `actor-id` is the caller, not the subject — see `grant-permission-to-user!`."
     [user-id permission-id actor-id]
     (when-let [conn (config-db/get-conn)]
       (ui-common/ensure-config-ui-admin! @conn actor-id)
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

(e/defn PermissionUserList [permission-id !refresh admin?]
  (e/client
   (let [refresh (e/watch !refresh)
         users (e/server (get-permission-users permission-id (e/client refresh)))]
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
           (when admin?
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
                  (e/server (revoke-permission-from-user! (:user/id user) permission-id
                                                          (:user/id e/http-request)))
                  (swap! !refresh inc)
                  (t))))))))))))

(e/defn AddUserToPermission [permission-id !refresh]
  (e/client
   (let [!selected-user (atom nil)
         selected-user (e/watch !selected-user)
         refresh-token (e/watch !refresh)
         all-users (e/server (get-all-users))
         existing-users (e/server (set (map :user/id (get-permission-users permission-id (e/client refresh-token)))))
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
           (e/server (grant-permission-to-user! selected-user permission-id
                                                (:user/id e/http-request)))
           (reset! !selected-user nil)
           (swap! !refresh inc)
           (t))))))))

(e/defn CreateUserWithPermission
  "Create a user who has never logged in, and grant them this permission (#573).

   ⚠️ THE CONTROL ABOVE THIS ONE CANNOT DO THIS, AND THAT IS THE WHOLE GAP.
   `AddUserToPermission` populates its dropdown from `get-all-users`, so it can
   only offer people who already exist. Nothing in the console created a user,
   and logging in does not: `can-login?` returns \"User not found\" and creates
   nothing. To appear in the dropdown you had to exist; to exist, someone had to
   POST /console-api/users by hand.

   ⚠️ THE ERROR IS RENDERED, NOT LOGGED. A duplicate address is the expected
   mistake here — the admin cannot see from this input whether the person is
   already in the dropdown above. Swallowing that would make \"already added\"
   look identical to \"added\", which is the failure mode that lets a broken
   control pass for a working one."
  [permission-id !refresh]
  (e/client
   (let [!email (atom "")
         !status (atom nil)
         !pending (atom nil)
         email (e/watch !email)
         status (e/watch !status)
         pending (e/watch !pending)
         ready? (not (str/blank? (str/trim (or email ""))))]
     (dom/div
      (dom/props {:style {:margin-top "0.75rem"
                          :padding-top "0.75rem"
                          :border-top "1px dashed #e5e7eb"}})
      (dom/div
       (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :margin-bottom "0.375rem"}})
       (dom/text "Or add someone who has never logged in:"))
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem"}})
       (dom/input
        (dom/props {:type "email"
                    :placeholder "name@example.com"
                    :value (or email "")
                    :style {:flex "1"
                            :padding "0.5rem"
                            :border "1px solid #d1d5db"
                            :border-radius "4px"}})
        (dom/On "change" #(reset! !email (.. % -target -value)) nil)
        (dom/On "input" #(reset! !email (.. % -target -value)) nil))
       (dom/button
        (dom/props {:style {:padding "0.5rem 1rem"
                            :background (if ready? "#3b82f6" "#e5e7eb")
                            :color (if ready? "white" "#9ca3af")
                            :border "none"
                            :border-radius "4px"
                            :cursor (if ready? "pointer" "not-allowed")}})
        (dom/text "Create and add")
        ;; The button only signals intent; it does not call the server. An
        ;; `e/server` call inline in the click branch races the client state
        ;; update, so the write can land while the UI shows nothing.
        ;; See CLAUDE.md, "Pending Signal Pattern".
        (let [[t _err] (e/Token (dom/On "click" identity nil))]
          (when (and t ready?)
            (reset! !pending (str/trim (or email "")))
            (t)))))
      ;; The reactive half: it runs because :pending changed, performs the
      ;; server work, and only then writes the client state that depends on it.
      (when-some [pending-email (not-empty (or pending ""))]
        (let [result (e/server (create-user-and-grant! (e/client pending-email)
                                                       permission-id
                                                       (:user/id e/http-request)))]
          (when (some? result)
            (e/client
             (if-let [err (:error result)]
               (reset! !status {:kind :error :text err})
               (do (reset! !status {:kind :ok
                                    :text (str "Created " (:email result)
                                               " and granted this permission.")})
                   (reset! !email "")
                   (swap! !refresh inc)))
             (reset! !pending nil)))))
      (when status
        (dom/div
         (dom/props {:style {:margin-top "0.5rem"
                             :font-size "0.75rem"
                             :color (if (= :error (:kind status)) "#b91c1c" "#15803d")}})
         (dom/text (:text status))))))))

(e/defn PermissionDetails [permission-id !refresh-outer]
  (e/client
   (let [!refresh (atom 0)
         refresh (e/watch !refresh)
         ;; ⚠️ READ ON THE SERVER, FROM THE REQUEST — not passed in, and not
         ;; round-tripped through client scope where a modified client could
         ;; choose its own answer. This value decides only what is OFFERED;
         ;; every write re-reads the actor server-side and refuses on its own
         ;; (`ensure-config-ui-admin!`), so hiding the controls is the
         ;; explanation, not the enforcement.
         admin? (e/server (boolean (when-let [conn (config-db/get-conn)]
                                     (perms/is-admin? @conn (:user/id e/http-request)))))]
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

      (PermissionUserList permission-id !refresh admin?)
      (if admin?
        (e/client
         (AddUserToPermission permission-id !refresh)
         (CreateUserWithPermission permission-id !refresh))
        (dom/div
         (dom/props {:style {:margin-top "1rem"
                             :padding-top "1rem"
                             :border-top "1px solid #e5e7eb"
                             :font-size "0.75rem"
                             :color "#6b7280"}})
         (dom/text "Changing who holds a permission requires the admin-full permission.")))))))

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
