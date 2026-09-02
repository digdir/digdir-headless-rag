(ns digdir.auth.ui
  "User profile UI components."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [digdir.i18n :as i18n]
            #?(:clj [digdir.auth.core :as auth])))

;; =========User Profile Menu=========

(e/defn ProfileMenu [user-email user-language]
  (let [user-id (e/server (:user/id e/http-request))]
    (e/client
     (let [!open (atom false)
           open (e/watch !open)
           language (i18n/normalize-language user-language)
           current-language-label (if (= language :nb) "Norsk" "English")
           next-language (if (= language :nb) :en :nb)
           next-language-label (if (= next-language :nb) "Norsk" "English")]
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
           (dom/button
            (dom/props {:type "button"
                        :style {:display "block"
                                :width "100%"
                                :padding "0.75rem 1rem"
                                :border "none"
                                :border-bottom "1px solid #e2e8f0"
                                :background "#f8fafc"
                                :color "#0D1B2A"
                                :text-align "left"
                                :font-size "0.875rem"
                                :cursor "pointer"}})
            (dom/text (i18n/t :profile/language-option
                              current-language-label
                              next-language-label))
            (let [[tok _] (e/Token (dom/On "click" identity nil))]
              (when tok
                (e/server (auth/set-user-preferred-language! (e/client user-id)
                                                             (name (e/client next-language))))
                ;; Queue the reload only after the server transaction returns,
                ;; then acknowledge the event. Invoking the token removes this
                ;; reactive branch, so no code may depend on running after it.
                (js/setTimeout (fn [] (.reload js/location)) 0)
                (tok))))
           ;; Logout link
           (dom/a
            (dom/props {:href "/logout"
                        :style {:display "block"
                                :padding "0.75rem 1rem"
                                :color "#0D1B2A"
                                :text-decoration "none"
                                :font-size "0.875rem"
                                :cursor "pointer"}})
            (dom/text (i18n/t :profile/logout))))))))))
