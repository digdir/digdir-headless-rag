(ns digdir.auth.views
  "HTML view templates for authentication and error pages."
  (:require
   [digdir.i18n :refer [t]]
   [hiccup2.core :as h]
   [ring.util.response :as res]))

(defn- html-response
  "Build an explicit HTML Ring response.

   Jetty must not infer the media type from body contents, especially when
   `X-Content-Type-Options: nosniff` is enabled on every response."
  [body]
  (-> (res/response body)
      (res/content-type "text/html; charset=utf-8")))

(defn confirm-email-page
  "Renders the email confirmation page with a code input form.

   `dev-confirmation-code`, when present, comes only from the dev delivery
   fallback and prefills the input. The normal production/email path passes
   nil and renders no code."
  ([email] (confirm-email-page email nil))
  ([email dev-confirmation-code]
   (html-response
    (str (h/html
         [:html
          [:head
           [:meta {:charset "utf-8"}]
           [:title (t :auth/check-email-inbox)]
           [:link {:rel "stylesheet"
                   :href "/admin_app/styles.css"}]
           [:link {:rel "icon"
                   :type "image/svg+xml"
                   :href "digdir_icon.svg"}]]
          [:body {:class "flex justify-center items-center min-h-screen bg-[#F2F4F7]"}
           [:div {:class "w-[450px] p-9 bg-white flex flex-col gap-6 rounded-lg shadow-sm"}
            [:div {:class "flex flex-col gap-2"}
              [:h1 {:class "text-2xl font-semibold text-[#0D1B2A]"} (t :auth/check-email-inbox)]
              [:p {:class "text-[#59626F]"}
               (t (if dev-confirmation-code
                    :auth/dev-code-provided-for
                    :auth/code-sent-to)) " "
               [:span {:class "font-medium text-[#0D1B2A]"} email]
               "."]]
             (when dev-confirmation-code
               [:div {:data-testid "dev-login-notice"
                      :role "status"
                      :class "flex items-start gap-3 p-4 bg-[#E8F1F8] border border-[#4B7EA8] rounded-lg"}
                [:svg {:class "w-5 h-5 text-[#245B85] flex-shrink-0 mt-0.5"
                       :xmlns "http://www.w3.org/2000/svg"
                       :viewBox "0 0 20 20"
                       :fill "currentColor"
                       :aria-hidden "true"}
                 [:path {:fill-rule "evenodd"
                         :d "M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-3a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z"
                         :clip-rule "evenodd"}]]
                [:div {:class "flex flex-col gap-1"}
                 [:p {:class "font-semibold text-[#173B57]"}
                  (t :auth/dev-mode-title)]
                 [:p {:class "text-sm leading-relaxed text-[#24506F]"}
                  (t :auth/dev-mode-notice)]]])
             [:div {:class "flex flex-col gap-2"}
              [:p {:class "text-[#59626F]"} (t :auth/code-validity-notice)]]
            [:form {:action "/auth/confirm-email"
                    :method "post"
                    :class "flex flex-col gap-6"}
             [:div {:class "flex flex-col gap-2"}
              [:label {:for "confirmation-code" :class "text-sm font-medium text-[#0D1B2A]"} (t :auth/login-code-label)]
              [:input (cond-> {:type "text"
                               :id "confirmation-code"
                               :name "confirmation-code"
                               :placeholder "000000"
                               :required true
                               :maxlength "6"
                               :pattern "\\d{6}"
                               :inputmode "numeric"
                               :autocomplete "one-time-code"
                               :title (t :auth/code-input-title)
                               :autofocus true
                               :class "px-4 py-3 border border-[#B8BCC1] rounded-lg focus:outline-none focus:ring-2 focus:ring-[#0D1B2A] focus:border-transparent transition-colors font-mono text-lg tracking-wider"}
                        dev-confirmation-code
                        (assoc :value dev-confirmation-code))]]
             [:button {:type "submit"
                       :class "px-6 py-3 bg-[#0D1B2A] text-white rounded-lg hover:bg-[#1B2D3F] transition-colors font-medium"}
              (t :auth/login-button)]]]]])))))

(defn auth-model
  "Base template for authentication pages (signup/login)."
  [{:keys [title action error-message prefill-email]}]
  (str (h/html
        [:html
         [:head
          [:meta {:charset "utf-8"}]
          [:title title]
          [:link {:rel "stylesheet"
                  :href "/admin_app/styles.css"}]
          [:link {:rel "icon"
                  :type "image/svg+xml"
                  :href "digdir_icon.svg"}]]
         [:body {:class "flex justify-center items-center min-h-screen bg-[#F2F4F7]"}
          [:div {:class "w-[450px] p-9 bg-white flex flex-col gap-6 rounded-lg shadow-sm"}
           [:div {:class "flex flex-col gap-2"}
            [:p {:class "text-sm font-medium text-[#59626F]"} (t :app/title)]
            [:h1 {:class "text-2xl font-semibold text-[#0D1B2A]"} title]
            [:p {:class "text-[#59626F]"}
             (if (= action "/auth")
               (t :auth/create-account-message)
               (t :auth/login-message))]]
           (when error-message
             [:div {:class "flex items-start gap-3 p-4 bg-[#FCF2E2] border border-[#AD7214] rounded-lg"}
              [:svg {:class "w-5 h-5 text-[#AD7214] flex-shrink-0 mt-0.5"
                     :xmlns "http://www.w3.org/2000/svg"
                     :viewBox "0 0 20 20"
                     :fill "currentColor"}
               [:path {:fill-rule "evenodd"
                       :d "M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 5a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 5zm0 9a1 1 0 100-2 1 1 0 000 2z"
                       :clip-rule "evenodd"}]]
              [:p {:class "text-sm text-[#3C2807] leading-relaxed"} error-message]])
           [:form {:action "/auth"
                   :method "post"
                   :class "flex flex-col gap-6"}
            [:div {:class "flex flex-col gap-2"}
             [:label {:for "email" :class "text-sm font-medium text-[#0D1B2A]"} (t :auth/email-label)]
             [:input (merge {:type "email"
                             :id "email"
                             :name "email"
                             :placeholder (t :auth/email-placeholder)
                             :required true
                             :autofocus (not error-message)
                             :class "px-4 py-3 border border-[#B8BCC1] rounded-lg focus:outline-none focus:ring-2 focus:ring-[#0D1B2A] focus:border-transparent transition-colors"}
                            (when prefill-email {:value prefill-email}))]]
            [:button {:type "submit"
                      :class "px-6 py-3 bg-[#0D1B2A] text-white rounded-lg hover:bg-[#1B2D3F] transition-colors font-medium"}
             (t :auth/continue-button)]]]]])))

(defn email-signup
  "Renders the signup page."
  []
  (html-response
   (auth-model {:title (t :auth/create-account-title)
                :action "/auth"})))

(defn login
  "Renders the login page."
  []
  (html-response
   (auth-model {:title (t :auth/welcome-back-title)
                :action "/login"})))

(defn not-approved-page
  "Renders error page for users who cannot log in.
   Shows custom error reason if provided, otherwise falls back to default message."
  ([email] (not-approved-page email nil))
  ([email error-reason]
   (html-response
    (auth-model {:title (t :auth/welcome-back-title)
                 :action "/login"
                 :error-message (or error-reason (t :auth/email-not-approved))
                 :prefill-email email}))))

(defn invalid-code-page
  "Renders error page for invalid or expired confirmation codes."
  []
  (html-response
   (str (h/html
         [:html
          [:head
           [:meta {:charset "utf-8"}]
           [:title (t :auth/login-error)]
           [:link {:rel "stylesheet"
                   :href "/admin_app/styles.css"}]
           [:link {:rel "icon"
                   :type "image/svg+xml"
                   :href "digdir_icon.svg"}]]
          [:body {:class "flex justify-center items-center min-h-screen bg-[#F2F4F7]"}
           [:div {:class "w-[450px] p-9 bg-white flex flex-col gap-6 rounded-lg shadow-sm"}
            [:div {:class "flex flex-col gap-2"}
             [:h1 {:class "text-2xl font-semibold text-[#0D1B2A]"} (t :auth/login-error)]
             [:div {:class "flex items-start gap-3 p-4 bg-[#FCF2E2] border border-[#AD7214] rounded-lg"}
              [:svg {:class "w-5 h-5 text-[#AD7214] flex-shrink-0 mt-0.5"
                     :xmlns "http://www.w3.org/2000/svg"
                     :viewBox "0 0 20 20"
                     :fill "currentColor"}
               [:path {:fill-rule "evenodd"
                       :d "M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 5a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 5zm0 9a1 1 0 100-2 1 1 0 000 2z"
                       :clip-rule "evenodd"}]]
              [:p {:class "text-sm text-[#3C2807] leading-relaxed"} (t :auth/code-invalid-or-expired)]]]
            [:div {:class "flex flex-col gap-4"}
             [:a {:href "/auth"
                  :class "px-6 py-3 bg-[#0D1B2A] text-white rounded-lg hover:bg-[#1B2D3F] transition-colors font-medium text-center"}
              (t :auth/continue-button)]]]]]))))

(defn error-page
  "Generic error page renderer."
  [title message]
  (html-response (str "<html><body><h1>" title "</h1><p>" message "</p></body></html>")))
