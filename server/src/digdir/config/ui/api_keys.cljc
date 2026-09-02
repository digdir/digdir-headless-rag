(ns digdir.config.ui.api-keys
  "API key management UI components."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            #?(:clj [digdir.data.db :as db])
            #?(:clj [digdir.config.api-keys :as api-keys])
            #?(:clj [digdir.config.accessor :as cfg])
            #?(:clj [digdir.config.db :as config-db])))

;; Helper function to format timestamps
#?(:clj
   (defn format-timestamp [epoch-ms]
     (when epoch-ms
       (let [inst (java.time.Instant/ofEpochMilli epoch-ms)
             formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")
             zoned (.atZone inst (java.time.ZoneId/of "Europe/Oslo"))]
         (.format formatter zoned)))))

#?(:clj
   (defn days-until-expiry [expires-at]
     (when expires-at
       (let [now (System/currentTimeMillis)
             diff-ms (- expires-at now)]
         (if (neg? diff-ms)
           :expired
           (Math/ceil (/ diff-ms 1000 60 60 24)))))))

(defn mask-api-key
  "Masks an API key showing first 4 and last 3 characters with x's in between.
   E.g., 'rag_abc123xyz789def' becomes 'rag_a-xxxxxxxxx-def'"
  [api-key]
  (when api-key
    (let [len (count api-key)]
      (if (> len 7)
        (str (subs api-key 0 4) "-" (apply str (repeat (- len 7) "x")) "-" (subs api-key (- len 3)))
        api-key))))

(e/defn ScopeTag [scope]
  (let [scope-colors {:query {:bg "#dbeafe" :text "#1e40af"}
                      :ingest {:bg "#dcfce7" :text "#166534"}
                      :admin {:bg "#fef3c7" :text "#92400e"}}
        colors (get scope-colors scope {:bg "#e5e7eb" :text "#374151"})]
    (dom/span
     (dom/props {:style {:background (:bg colors)
                         :color (:text colors)
                         :padding "0.125rem 0.5rem"
                         :border-radius "9999px"
                         :font-size "0.75rem"
                         :font-weight "500"
                         :margin-right "0.25rem"}})
     (dom/text (name scope)))))

(e/defn ItemTag [text color-scheme]
  "Display a tag for tenant/environment/entity items."
  (let [colors (case color-scheme
                 :tenant {:bg "#e0e7ff" :text "#3730a3"}
                 :environment {:bg "#fce7f3" :text "#9d174d"}
                 :entity {:bg "#d1fae5" :text "#065f46"}
                 {:bg "#e5e7eb" :text "#374151"})]
    (dom/span
     (dom/props {:style {:background (:bg colors)
                         :color (:text colors)
                         :padding "0.125rem 0.5rem"
                         :border-radius "4px"
                         :font-size "0.75rem"
                         :font-weight "500"
                         :margin-right "0.25rem"
                         :margin-bottom "0.125rem"
                         :display "inline-block"}})
     (dom/text text))))

(e/defn MultiSelectDropdown [label items selected-set !selected-set]
  "Multi-select dropdown component."
  (e/client
   (let [!open (atom false)
         open (e/watch !open)]
     (dom/div
      (dom/props {:style {:position "relative" :margin-bottom "1rem"}})

      ;; Label
      (dom/label
       (dom/props {:style {:display "block" :font-weight "500" :margin-bottom "0.25rem"}})
       (dom/text label))

      ;; Dropdown trigger
      (dom/div
       (dom/props {:style {:border "1px solid #d1d5db"
                           :border-radius "4px"
                           :padding "0.5rem"
                           :background "white"
                           :cursor "pointer"
                           :min-height "2.5rem"}})
       (dom/On "click" (fn [_] (swap! !open not)) nil)

       (if (empty? selected-set)
         (dom/span
          (dom/props {:style {:color "#9ca3af"}})
          (dom/text "Select..."))
         (dom/div
          (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.25rem"}})
          (e/for [item (e/diff-by identity (sort selected-set))]
            (dom/span
             (dom/props {:style {:background "#e5e7eb"
                                 :padding "0.125rem 0.5rem"
                                 :border-radius "4px"
                                 :font-size "0.875rem"}})
             (dom/text item))))))

      ;; Dropdown panel
      (when open
        (dom/div
         (dom/props {:style {:position "absolute"
                             :top "100%"
                             :left "0"
                             :right "0"
                             :background "white"
                             :border "1px solid #d1d5db"
                             :border-radius "4px"
                             :box-shadow "0 4px 6px -1px rgba(0,0,0,0.1)"
                             :z-index "100"
                             :max-height "200px"
                             :overflow-y "auto"}})
         (if (empty? items)
           (dom/div
            (dom/props {:style {:padding "0.5rem" :color "#9ca3af" :font-style "italic"}})
            (dom/text "No items available"))
           (e/for [item (e/diff-by identity items)]
             (dom/label
              (dom/props {:style {:display "flex"
                                  :align-items "center"
                                  :padding "0.5rem"
                                  :cursor "pointer"
                                  :hover {:background "#f3f4f6"}}})
              (dom/input
               (dom/props {:type "checkbox"
                           :checked (contains? selected-set item)
                           :style {:margin-right "0.5rem"}})
               (dom/On "change" (fn [_]
                                  (swap! !selected-set
                                         (fn [s] (if (contains? s item)
                                                   (disj s item)
                                                   (conj s item))))) nil))
              (dom/text item))))))))))

(e/defn EntityMultiSelect [label all-tenants selected-tenants entities-by-tenant entity-names selected-entities !selected-entities]
  "Multi-select dropdown for entities, filtered by selected tenants."
  (e/client
   (let [!open              (atom false)
         open               (e/watch !open)
         ;; Get entities for selected tenants
         available-entities (into #{} (mapcat #(get entities-by-tenant %) selected-tenants))]
     (dom/div
      (dom/props {:style {:position      "relative"
                          :margin-bottom "1rem"}})

      ;; Label
      (dom/label
       (dom/props {:style {:display       "block"
                           :font-weight   "500"
                           :margin-bottom "0.25rem"}})
       (dom/text label))

      ;; Dropdown trigger
      (dom/div
       (dom/props {:style {:border        "1px solid #d1d5db"
                           :border-radius "4px"
                           :padding       "0.5rem"
                           :background    "white"
                           :cursor        "pointer"
                           :min-height    "2.5rem"}})
       (dom/On "click" (fn [_] (swap! !open not)) nil)

       (if (empty? selected-entities)
         (dom/span
          (dom/props {:style {:color "#9ca3af"}})
          (dom/text (if (empty? selected-tenants) "Select tenants first..." "Select entities...")))
         (dom/div
          (dom/props {:style {:display   "flex"
                              :flex-wrap "wrap"
                              :gap       "0.25rem"}})
          (e/for [entity-id (e/diff-by identity (sort selected-entities))]
            (dom/span
             (dom/props {:style {:background    "#d1fae5"
                                 :padding       "0.125rem 0.5rem"
                                 :border-radius "4px"
                                 :font-size     "0.875rem"}})
             (dom/text (or (get entity-names entity-id) entity-id)))))))

      ;; Dropdown panel
      (when (and open (seq selected-tenants))
        (dom/div
         (dom/props {:style {:position      "absolute"
                             :top           "100%"
                             :left          "0"
                             :right         "0"
                             :background    "white"
                             :border        "1px solid #d1d5db"
                             :border-radius "4px"
                             :box-shadow    "0 4px 6px -1px rgba(0,0,0,0.1)"
                             :z-index       "100"
                             :max-height    "200px"
                             :overflow-y    "auto"}})
         (if (empty? available-entities)
           (dom/div
            (dom/props {:style {:padding    "0.5rem"
                                :color      "#9ca3af"
                                :font-style "italic"}})
            (dom/text "No entities available for selected tenants"))
           (e/for [entity-id (e/diff-by identity (sort available-entities))]
             (dom/label
              (dom/props {:style {:display     "flex"
                                  :align-items "center"
                                  :padding     "0.5rem"
                                  :cursor      "pointer"}})
              (dom/input
               (dom/props {:type    "checkbox"
                           :checked (contains? selected-entities entity-id)
                           :style   {:margin-right "0.5rem"}})
               (dom/On "change" (fn [_]
                                  (swap! !selected-entities
                                         (fn [s] (if (contains? s entity-id)
                                                   (disj s entity-id)
                                                   (conj s entity-id))))) nil))
              (dom/span
               (dom/text (or (get entity-names entity-id) entity-id))))))))))))

(e/defn APIKeyRow [api-key entity-names]
  (let [key-id (:api-key/id api-key)
        api-key-value (:api-key/key api-key)
        is-revoked (:api-key/revoked api-key)
        expires-at (:api-key/expires-at api-key)
        days-left (e/server (days-until-expiry expires-at))
        is-expired (= days-left :expired)
        ;; Get multi-select values
        tenants (or (:api-key/tenants api-key) [])
        environments (or (:api-key/environments api-key) [])
        entities (or (:api-key/entities api-key) [])]
    (dom/tr
     (dom/props {:style {:opacity (if (or is-revoked is-expired) "0.6" "1")}})

     ;; Name
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (dom/div
       (dom/props {:style {:font-weight "500"}})
       (dom/text (:api-key/name api-key)))
      (dom/div
       (dom/props {:style {:font-size "0.75rem" :color "#6b7280" :font-family "monospace"}})
       (dom/text (str "ID: " key-id))))

     ;; Scopes
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (e/for [scope (e/diff-by identity (or (:api-key/scopes api-key) [:query]))]
        (ScopeTag scope)))

     ;; Tenants
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (if (empty? tenants)
        (dom/span
         (dom/props {:style {:color "#9ca3af" :font-size "0.75rem"}})
         (dom/text "All"))
        (dom/div
         (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.125rem"}})
         (e/for [tenant (e/diff-by identity tenants)]
           (ItemTag tenant :tenant)))))

     ;; Environments
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (if (empty? environments)
        (dom/span
         (dom/props {:style {:color "#9ca3af" :font-size "0.75rem"}})
         (dom/text "All"))
        (dom/div
         (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.125rem"}})
         (e/for [env (e/diff-by identity environments)]
           (ItemTag env :environment)))))

     ;; Entities
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (if (empty? entities)
        (dom/span
         (dom/props {:style {:color "#9ca3af" :font-size "0.75rem"}})
         (dom/text "All"))
        (dom/div
         (dom/props {:style {:display "flex" :flex-wrap "wrap" :gap "0.125rem"}})
         (e/for [entity-id (e/diff-by identity entities)]
           (ItemTag (or (get entity-names entity-id) entity-id) :entity)))))

     ;; Created
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb" :font-size "0.875rem"}})
      (dom/div (dom/text (e/server (format-timestamp (:api-key/created api-key)))))
      (dom/div
       (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
       (dom/text (str "by " (:api-key/created-by api-key)))))

     ;; Last Used & Usage Count
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb" :font-size "0.875rem"}})
      (if-let [last-used (:api-key/last-used api-key)]
        (dom/div
         (dom/div (dom/text (e/server (format-timestamp last-used))))
         (dom/div
          (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
          (dom/text (str (or (:api-key/usage-count api-key) 0) " uses"))))
        (dom/text "Never")))

     ;; Expiration
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb" :font-size "0.875rem"}})
      (cond
        (not expires-at)
        (dom/span
         (dom/props {:style {:color "#6b7280"}})
         (dom/text "Never"))

        is-expired
        (dom/span
         (dom/props {:style {:color "#dc2626" :font-weight "500"}})
         (dom/text "Expired"))

        :else
        (dom/span
         (dom/props {:style {:color (if (< days-left 7) "#d97706" "#059669")}})
         (dom/text (str days-left " days")))))

     ;; Status & Actions
     (dom/td
      (dom/props {:style {:padding "0.75rem" :border-bottom "1px solid #e5e7eb"}})
      (if is-revoked
        (dom/span
         (dom/props {:style {:color "#dc2626" :font-size "0.875rem" :font-weight "500"}})
         (dom/text "Revoked"))
        (ks/Button {:data-size "sm"
                    :data-variant "tertiary"
                    :data-color "danger"}
                   (e/fn []
                     (dom/text "Revoke")
                     (let [[t err] (e/Token (dom/On "click" identity nil))]
                       (when t
                         (case (and (e/client (js/confirm "Are you sure you want to revoke this API key? This cannot be undone."))
                                    (e/server (api-keys/revoke-api-key (db/get-conn) key-id)))
                           (t)))))))))))

(e/defn NewAPIKeyModal [!show-modal !new-key-data]
  (let [;; Get tenants and entities data from server
        all-tenants (e/server (config-db/list-tenants @(db/get-conn)))
        entities-by-tenant (e/server
                            (let [conn (db/get-conn)
                                  db @conn
                                  tenants (config-db/list-tenants db)]
                              (config-db/get-entities-by-tenant db tenants)))
        entity-names (e/server
                      (let [conn (db/get-conn)
                            db @conn
                            tenants (config-db/list-tenants db)
                            all-entities (distinct (mapcat #(config-db/list-entities db %) tenants))]
                        (reduce (fn [acc t]
                                  (merge acc (config-db/get-entity-names db t (config-db/list-entities db t))))
                                {}
                                tenants)))
        all-environments ["prod" "staging" "test" "dev"]

        ;; Form state
        !name (atom "")
        !selected-tenants (atom #{})
        !selected-environments (atom #{})
        !selected-entities (atom #{})
        !scopes (atom #{:query})
        !expires-days (atom "")

        ;; Watched values
        name-val (e/watch !name)
        selected-tenants (e/watch !selected-tenants)
        selected-environments (e/watch !selected-environments)
        selected-entities (e/watch !selected-entities)
        scopes-val (e/watch !scopes)
        expires-days-val (e/watch !expires-days)

        show-new-key-data! (fn [result]
                             (reset! !show-modal false)
                             (reset! !new-key-data result))]

    ;; Modal backdrop
    (dom/div
     (dom/props {:style {:position "fixed"
                         :top "0"
                         :left "0"
                         :right "0"
                         :bottom "0"
                         :background "rgba(0,0,0,0.5)"
                         :display "flex"
                         :align-items "center"
                         :justify-content "center"
                         :z-index "1000"}})
     (dom/On "click" #(reset! !show-modal false) nil)

     ;; Modal content
     (dom/div
      (dom/props {:style {:background "white"
                          :border-radius "8px"
                          :padding "1.5rem"
                          :max-width "600px"
                          :width "100%"
                          :max-height "90vh"
                          :overflow-y "auto"}})
      (dom/On "click" #(.stopPropagation %) nil)

      (ks/Heading {:level 3 :style {:margin-bottom "1rem"}}
                  (e/fn [] (dom/text "Create New API Key")))

      ;; Name field
      (dom/div
       (dom/props {:style {:margin-bottom "1rem"}})
       (dom/label
        (dom/props {:style {:display "block" :font-weight "500" :margin-bottom "0.25rem"}})
        (dom/text "Name *"))
       (dom/input
        (dom/props {:type "text"
                    :placeholder "e.g., Production Integration"
                    :value name-val
                    :style {:width "100%"
                            :padding "0.5rem"
                            :border "1px solid #d1d5db"
                            :border-radius "4px"}})
        (dom/On "input" #(reset! !name (.. % -target -value)) nil)))

      ;; Tenants multi-select
      (MultiSelectDropdown "Tenants" all-tenants selected-tenants !selected-tenants)

      ;; Environments multi-select
      (MultiSelectDropdown "Environments" all-environments selected-environments !selected-environments)

      ;; Entities multi-select (filtered by selected tenants)
      (EntityMultiSelect "Entities" all-tenants selected-tenants entities-by-tenant entity-names selected-entities !selected-entities)

      ;; Scopes selection
      (dom/div
       (dom/props {:style {:margin-bottom "1rem"}})
       (dom/label
        (dom/props {:style {:display "block" :font-weight "500" :margin-bottom "0.5rem"}})
        (dom/text "Scopes *"))
       (dom/div
        (dom/props {:style {:display "flex" :gap "1rem" :flex-wrap "wrap"}})
        (e/for [[scope desc] (e/diff-by first [[:query "Read/query the RAG API"]
                                                [:ingest "Ingest documents"]
                                                [:admin "Administrative operations"]])]
          (dom/label
           (dom/props {:style {:display "flex" :align-items "center" :gap "0.5rem" :cursor "pointer"}})
           (dom/input
            (dom/props {:type "checkbox"
                        :checked (contains? scopes-val scope)})
            (dom/On "change" #(swap! !scopes (fn [s] (if (contains? s scope)
                                                        (disj s scope)
                                                        (conj s scope)))) nil))
           (dom/span (dom/text (name scope)))
           (dom/span
            (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
            (dom/text (str "(" desc ")")))))))

      ;; Expiration field
      (dom/div
       (dom/props {:style {:margin-bottom "1.5rem"}})
       (dom/label
        (dom/props {:style {:display "block" :font-weight "500" :margin-bottom "0.25rem"}})
        (dom/text "Expires in (days)"))
       (dom/input
        (dom/props {:type "number"
                    :placeholder "Leave empty for no expiration"
                    :value expires-days-val
                    :min "1"
                    :style {:width "100%"
                            :padding "0.5rem"
                            :border "1px solid #d1d5db"
                            :border-radius "4px"}})
        (dom/On "input" #(reset! !expires-days (.. % -target -value)) nil)))

      ;; Buttons
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem" :justify-content "flex-end"}})

       (ks/Button {:data-variant "secondary"}
                  (e/fn []
                    (dom/text "Cancel")
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (case (reset! !show-modal false)
                          (t))))))

       (ks/Button {:data-variant "primary"
                   :disabled (or (str/blank? name-val) (empty? scopes-val))}
                  (e/fn []
                    (dom/text "Create Key")
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (let [expires-days-num (when (not (str/blank? expires-days-val))
                                                 (parse-long expires-days-val))
                              scopes-vec (vec scopes-val)
                              tenants-vec (vec selected-tenants)
                              envs-vec (vec selected-environments)
                              entities-vec (vec selected-entities)
                              result (e/server
                                      (let [created-by (or (:user/email e/http-request) "unknown")]
                                        (api-keys/create-api-key! (db/get-conn)
                                                                  name-val
                                                                  created-by
                                                                  {:tenants tenants-vec
                                                                   :environments envs-vec
                                                                   :entities entities-vec
                                                                   :scopes scopes-vec
                                                                   :expires-in-days expires-days-num})))]
                          ;; First set the key data, then close the create modal
                          (case (show-new-key-data! result)
                            (t))))))))))))

(e/defn KeyCreatedModal [!new-key-data]
  (let [key-data (e/watch !new-key-data)]
    ;; Modal backdrop
    (dom/div
     (dom/props {:style {:position "fixed"
                         :top "0"
                         :left "0"
                         :right "0"
                         :bottom "0"
                         :background "rgba(0,0,0,0.5)"
                         :display "flex"
                         :align-items "center"
                         :justify-content "center"
                         :z-index "1000"}})

     ;; Modal content
     (dom/div
      (dom/props {:style {:background "white"
                          :border-radius "8px"
                          :padding "1.5rem"
                          :max-width "600px"
                          :width "100%"}})

      (ks/Heading {:level 3 :style {:margin-bottom "0.5rem"}}
                  (e/fn [] (dom/text "API Key Created Successfully")))

      (ks/Alert {:data-color "warning" :style {:margin-bottom "1rem"}}
                (e/fn []
                  (dom/text "Copy this key now. You won't be able to see it again!")))

      ;; Key display
      (dom/div
       (dom/props {:style {:background "#f3f4f6"
                           :padding "1rem"
                           :border-radius "6px"
                           :font-family "monospace"
                           :font-size "0.875rem"
                           :word-break "break-all"
                           :margin-bottom "1rem"}})
       (dom/text (:api-key key-data)))

      ;; Action buttons
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem" :justify-content "flex-end"}})

       ;; Copy button
       (ks/Button {:data-variant "secondary"}
                  (e/fn []
                    (dom/text "Copy to Clipboard")
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (case (e/client
                               (-> (js/navigator.clipboard.writeText (:api-key key-data))
                                   (.then #(js/alert "API key copied to clipboard!"))
                                   (.catch #(js/alert "Failed to copy. Please copy manually."))))
                          (t))))))

       ;; Download button
       (ks/Button {:data-variant "secondary"}
                  (e/fn []
                    (dom/text "Download as JSON")
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        (case (e/client
                               #?(:cljs
                                  (let [json-data (js/JSON.stringify (clj->js {:api_key (:api-key key-data)
                                                                               :name (:name key-data)
                                                                               :created_at (js/Date.)}) nil 2)
                                        blob (js/Blob. #js [json-data] #js {:type "application/json"})
                                        url (js/URL.createObjectURL blob)
                                        a (js/document.createElement "a")]
                                    (set! (.-href a) url)
                                    (set! (.-download a) (str "api-key-" (:name key-data) ".json"))
                                    (.click a)
                                    (js/URL.revokeObjectURL url))))
                          (t))))))

       ;; Done button
       (ks/Button {:data-variant "primary"}
                  (e/fn []
                    (dom/text "Done")
                    (let [[t err] (e/Token (dom/On "click" identity nil))]
                      (when t
                        ;; Clear the modal by resetting the atom
                        (case (reset! !new-key-data nil)
                          (t)))))))))))

(e/defn APIKeys []
  (let [;; Server-side data - watch the db for reactivity
        api-keys-list (e/server
                       (let [conn (db/get-conn)]
                         (vec (sort-by :api-key/created > (api-keys/list-all-api-keys (e/watch conn))))))
        ;; Get entity names for display
        entity-names (e/server
                      (let [conn (db/get-conn)
                            db @conn
                            tenants (config-db/list-tenants db)]
                        (reduce (fn [acc t]
                                  (merge acc (config-db/get-entity-names db t (config-db/list-entities db t))))
                                {}
                                tenants)))
        ;; Client-side UI state atoms
        !show-create-modal (atom false)
        show-create-modal (e/watch !show-create-modal)
        !new-key-data (atom nil)
        new-key-data (e/watch !new-key-data)]
    (dom/div
     (dom/props {:style {:padding "1rem"
                         :max-width "1600px"}})

     (dom/div
      (dom/props {:style {:display "flex"
                          :justify-content "space-between"
                          :align-items "center"
                          :margin-bottom "1rem"}})

      (ks/Heading {:level 2}
                  (e/fn [] (dom/text "API Keys")))

      (ks/Button {:data-variant "primary"}
                 (e/fn []
                   (dom/text "Create New Key")
                   (let [[t err] (e/Token (dom/On "click" identity nil))]
                     (when t
                       (case (reset! !show-create-modal true)
                         (t)))))))

     (ks/Card {}
              (e/fn []
                (ks/CardBlock {}
                              (e/fn []
                                (ks/Paragraph {:style {:margin-bottom "1rem"}}
                                              (e/fn [] (dom/text "Manage API keys for accessing the RAG API. Keys are used for authentication and can have different permission scopes.")))

                                (if (empty? api-keys-list)
                                  (dom/div
                                   (dom/props {:style {:text-align "center"
                                                       :padding "2rem"
                                                       :color "#6b7280"}})
                                   (dom/text "No API keys yet. Create one to get started."))

                                  ;; API Keys table
                                  (dom/div
                                   (dom/props {:style {:overflow-x "auto"}})
                                   (dom/table
                                    (dom/props {:style {:width "100%"
                                                        :border-collapse "collapse"}})
                                    (dom/thead
                                     (dom/tr
                                      (dom/props {:style {:background "#f9fafb"}})
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Name"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Scopes"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Tenants"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Environments"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Entities"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Created"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Last Used"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Expires"))
                                      (dom/th (dom/props {:style {:padding "0.75rem" :text-align "left" :font-weight "600" :border-bottom "2px solid #e5e7eb"}}) (dom/text "Actions"))))
                                    (dom/tbody
                                     (e/for [api-key (e/diff-by :api-key/id api-keys-list)]
                                       (APIKeyRow api-key entity-names))))))))))

     ;; Modals
     (when show-create-modal
       (NewAPIKeyModal !show-create-modal !new-key-data))

     (when new-key-data
       (KeyCreatedModal !new-key-data)))))
