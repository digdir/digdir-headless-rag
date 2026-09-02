(ns digdir.auth.ui
  "User profile UI components."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]))

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

