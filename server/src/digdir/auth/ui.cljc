(ns digdir.auth.ui
  "Access control - email domain whitelist management and user profile UI."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            #?(:clj [tick.core :as tick])
            #?(:clj [datahike.api :as d])
            #?(:clj [digdir.data.db :as db])))

;; =========User Profile Menu=========

(e/defn ProfileMenu [user-email]
  (e/client
   (let [!open (atom false)
         open (e/watch !open)]
     (dom/div
      (dom/props {:style {:position "relative"
                          :margin-left "auto"}})
      ;; Profile icon button
      (dom/button
       (dom/props {:style {:display "flex"
                           :align-items "center"
                           :justify-content "center"
                           :width "28px"
                           :height "28px"
                           :border-radius "50%"
                           :border "none"
                           :background (if open "#475569" "#64748b")
                           :cursor "pointer"
                           :padding "0"
                           :font-size "12px"
                           :font-weight "600"
                           :color "#fff"
                           :text-transform "uppercase"}})
       (dom/On "click" (fn [_] (swap! !open not)) nil)
       ;; Show first letter of email as avatar
       (dom/text (subs (or user-email "?") 0 1)))
      ;; Dropdown menu
      (when open
        (dom/div
         (dom/props {:style {:position "absolute"
                             :top "100%"
                             :right "0"
                             :margin-top "0.5rem"
                             :min-width "200px"
                             :background "#fff"
                             :border "1px solid #e2e8f0"
                             :border-radius "6px"
                             :box-shadow "0 4px 6px -1px rgba(0,0,0,0.1)"
                             :z-index "1000"}})
         ;; Email (non-clickable)
         (dom/div
          (dom/props {:style {:padding "0.75rem 1rem"
                              :border-bottom "1px solid #e2e8f0"
                              :color "#64748b"
                              :font-size "0.8rem"}})
          (dom/text user-email))
         ;; Logout link
         (dom/a
          (dom/props {:href "/logout"
                      :style {:display "block"
                              :padding "0.75rem 1rem"
                              :color "#0D1B2A"
                              :text-decoration "none"
                              :font-size "0.875rem"
                              :cursor "pointer"}})
          (dom/text "Logout"))))))))

#?(:clj
   (defn get-allowed-domains []
     (let [conn (db/get-conn)
           domains (d/q '[:find [?domain ...]
                          :where
                          [?e :allowed-domain/domain ?domain]]
                        @conn)]
       (vec (sort domains)))))

(defn add-allowed-domain! [domain]
  #?(:clj
     (let [normalized-domain (if (str/starts-with? domain "@")
                               domain
                               (str "@" domain))
           conn (db/get-conn)]
       (d/transact conn [{:allowed-domain/domain normalized-domain
                          :updated-at (tick/inst)}])
       true)
     :cljs false))

(defn remove-allowed-domain! [domain]
  #?(:clj
     (let [conn (db/get-conn)]
       (when-let [eid (d/q '[:find ?e .
                             :in $ ?domain
                             :where
                             [?e :allowed-domain/domain ?domain]]
                           @conn
                           domain)]
         (d/transact conn [[:db/retractEntity eid]])
         true))
     :cljs false))

(e/defn AccessControl []
  (let [email-domains (e/server (let [conn (db/get-conn)]
                                  (vec (sort (d/q '[:find [?domain ...]
                                                    :where
                                                    [?e :allowed-domain/domain ?domain]]
                                                  (e/watch conn))))))
        !new-domain (atom "")
        new-domain (e/watch !new-domain)]
    (dom/div
     (dom/props {:style {:padding "1rem"
                         :max-width "800px"}})

     (ks/Heading {:level 2 :style {:margin-bottom "1rem"}}
                 (e/fn [] (dom/text "Access Control")))

     (ks/Card {:style {:margin-bottom "1rem"}}
              (e/fn []
                (ks/CardBlock {}
                              (e/fn []
                                (ks/Heading {:level 3 :style {:margin-bottom "1rem"}}
                                            (e/fn [] (dom/text "Allowed Email Domains")))

                                (ks/Paragraph {:style {:margin-bottom "1rem"}}
                                              (e/fn [] (dom/text "Users with email addresses from these domains will be granted access to the system.")))

                                (dom/div
                                 (dom/props {:style {:margin-bottom "1rem"}})

                                 (e/for [domain (e/diff-by identity email-domains)]
                                   (dom/div
                                    (dom/props {:style {:display "flex"
                                                        :align-items "center"
                                                        :gap "0.5rem"
                                                        :margin-bottom "0.5rem"}})

                                    (dom/div
                                     (dom/props {:style {:flex "1"
                                                         :padding "0.5rem 1rem"
                                                         :background "#f3f4f6"
                                                         :border "1px solid #d1d5db"
                                                         :border-radius "4px"}})
                                     (dom/text domain))

                                    (ks/Button {:data-size "sm"
                                                :data-variant "tertiary"
                                                :data-color "danger"}
                                               (e/fn []
                                                 (dom/text "Remove")
                                                 (let [[t err] (e/Token (dom/On "click" identity nil))]
                                                   (when t
                                                     (case (and (e/client (js/confirm "Er du sikker på at du vil fjerne dette domenet?"))
                                                                (e/server (remove-allowed-domain! domain)))
                                                       (t)))))))))

                                (dom/div
                                 (dom/props {:style {:display "flex"
                                                     :gap "0.5rem"
                                                     :margin-top "1rem"}})

                                 (dom/input
                                  (dom/props {:placeholder "Enter email domain (e.g., example.com or @example.com)"
                                              :value new-domain
                                              :style {:flex "1"
                                                      :padding "0.5rem"
                                                      :border "1px solid #d1d5db"
                                                      :border-radius "4px"}})
                                  (dom/On "input" #(reset! !new-domain (.. % -target -value)) nil))

                                 (ks/Button {:data-size "sm"
                                             :data-variant "primary"}
                                            (e/fn []
                                              (dom/text "Add Domain")
                                              (let [[t err] (e/Token (dom/On "click" identity nil))]
                                                (when t
                                                  (case
                                                   (when (not (str/blank? new-domain))
                                                     (let [domain-to-add (if (str/starts-with? new-domain "@")
                                                                           new-domain
                                                                           (str "@" new-domain))]
                                                       (case (e/server (add-allowed-domain! domain-to-add))
                                                         (reset! !new-domain ""))))
                                                    (t)))))))))))

     (ks/Alert {:data-color "info"}
               (e/fn []
                 (dom/text "Changes to access control settings are saved automatically."))))))
