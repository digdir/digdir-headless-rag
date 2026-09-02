(ns digdir.playground.ui
  "UI components for the RAG Playground feature."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [clojure.string :as str]
            #?(:clj [digdir.auth.core :as auth])
            #?(:clj [digdir.agents.db :as agents-db])
            #?(:clj [digdir.config.accessor :as cfg])
            #?(:clj [digdir.config.core :as config-core])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.config.permissions :as perms])
            #?(:clj [digdir.data.db :as db])
            #?(:clj [digdir.playground.core :as playground])
            #?(:clj [digdir.skills.api :as skills-api])
            #?(:clj [taoensso.timbre :as timbre])
            [clojure.edn :as edn]
            [lentes.core :as l]
            [digdir.playground.chat-session :as chat-session]
            [digdir.playground.citations :as citations]
            [digdir.playground.diagnostics :as diagnostics]
            [digdir.playground.ui.components :as base]
            [digdir.playground.ui.common :as common]
            [digdir.playground.ui.observability :as observability]
            [digdir.playground.ui.styles :as styles]
            [digdir.i18n :refer [t]]))

;; =========Styles=========

(def card-style styles/card-style)
(def label-style styles/label-style)
(def input-style styles/input-style)
(def select-style styles/select-style)
(def textarea-style styles/textarea-style)
(def modal-backdrop-style styles/modal-backdrop-style)
(def modal-content-style styles/modal-content-style)

(def default-skill-graphs common/default-skill-graphs)

(defn normalize-skill-graph-option
  "Normalize a skill graph map into a UI option map."
  [sg]
  (common/normalize-skill-graph-option sg))

(defn normalize-dataset-scope-option
  "Normalize dataset scope options into stable {:value :label} maps for the client."
  [option]
  (common/normalize-dataset-scope-option option))

#?(:clj
   (defn load-skill-graph-options-data
     "Load skill graph options and debug metadata for the Playground config panel."
     []
     (try
       (common/load-skill-graph-options-data)
       (catch Exception e
         (println "Playground skill graph load failed:" (.getMessage e))
         {:options default-skill-graphs
          :debug {:source :fallback-error
                  :raw-count 0
                  :option-count (count default-skill-graphs)
                  :error (.getMessage e)}}))))

;; =========Chat UI Styles=========

(def message-bubble-base-style styles/message-bubble-base-style)
(def user-message-style styles/user-message-style)
(def assistant-message-style styles/assistant-message-style)
(def message-thread-style styles/message-thread-style)
(def chat-input-style styles/chat-input-style)
(def conversation-sidebar-style styles/conversation-sidebar-style)
(def sidebar-item-style styles/sidebar-item-style)

(defn default-chat-config
  "Default chat config without dataset-scope-specific retrieval/rerank overrides."
  []
  (common/default-chat-config))

(defn initialize-chat-config
  "Initialize chat config from the selected dataset's resolved defaults."
  [config dataset-config]
  (common/initialize-chat-config config dataset-config))

#?(:clj
   (defn resolve-selected-dataset-config
     "Load the selected dataset plus V2 runtime skill defaults when agent scope is available.
      This no longer reads legacy tuple-scoped runtime skill config."
     [tenant dataset-config-key agent-id]
     (common/resolve-selected-dataset-config tenant dataset-config-key agent-id)))

(defn effective-chat-config
  "Merge the current config with the resolved skill graph without mutating UI state."
  [config effective-skill-graph]
  (common/effective-chat-config config effective-skill-graph))

(defn dataset-scope-label
  "Render a persisted dataset scope as tenant / dataset-config-key."
  [{:keys [tenant dataset-config-key]}]
  (common/dataset-scope-label {:tenant tenant
                               :dataset-config-key dataset-config-key}))

(defn dataset-ref-label
  "Compatibility alias for the dataset-scope label helper."
  [{:keys [tenant dataset-config-key]}]
  (dataset-scope-label {:tenant tenant
                        :dataset-config-key dataset-config-key}))

(defn conversation-dataset-scope
  "Project a persisted conversation onto the canonical persisted dataset scope."
  [conversation]
  (common/conversation-dataset-scope conversation))

(defn conversation-dataset-ref
  "Compatibility alias for the dataset-scope conversation helper."
  [conversation]
  (conversation-dataset-scope conversation))

(defn conversation-scope-label
  "Display conversations as agent-first, with dataset scope as context."
  [conversation agent-names]
  (common/conversation-scope-label conversation agent-names))

(defn preferred-agent-id
  "Choose the effective playground agent from explicit selection, then skill graph, then first enabled agent."
  [selected-agent-id config enabled-agents]
  (common/preferred-agent-id selected-agent-id config enabled-agents))

(defn displayed-agent-id
  "Choose the agent ID to show in the selector. Prefer the explicit selection when it is still valid."
  [selected-agent-id effective-agent-id enabled-agents]
  (common/displayed-agent-id selected-agent-id effective-agent-id enabled-agents))

(defn restrict-skill-graph-data
  "Limit visible skill graph options to those allowed by the selected agent."
  [skill-graph-data agent]
  (common/restrict-skill-graph-data skill-graph-data agent))

(defn effective-skill-graph-id
  "Pick a valid skill graph for the selected agent and currently loaded options."
  [config skill-graph-data agent]
  (common/effective-skill-graph-id config skill-graph-data agent))

(defn dataset-scope-allowed?
  "True when a selected dataset scope is permitted by an agent's explicit dataset grants."
  [dataset-scopes tenant dataset-config-key]
  (common/dataset-scope-allowed? dataset-scopes tenant dataset-config-key))

(defn dataset-ref-allowed?
  "Compatibility alias for the dataset-scope grant helper."
  [dataset-scopes tenant dataset-config-key]
  (dataset-scope-allowed? dataset-scopes tenant dataset-config-key))

(defn dataset-scope-options
  "Build visible dataset-scope options for a tenant."
  [tenant agent-dataset-scopes]
  (common/dataset-scope-options tenant agent-dataset-scopes))

(defn dataset-config-options
  "Compatibility alias for the dataset-scope options helper."
  [tenant agent-dataset-scopes]
  (dataset-scope-options tenant agent-dataset-scopes))

(defn allowed-option-values
  "List visible values for one dataset dimension from explicit allowed dataset scopes."
  [dataset-scopes k]
  (common/allowed-option-values dataset-scopes k))

(defn eligible-agents-for-scope
  "Keep agents that are authorized for the selected organization and dataset."
  [enabled-agents tenant dataset-config-key]
  (filterv
   (fn [agent]
     (dataset-scope-allowed? (:allowed-dataset-scopes agent)
                             tenant dataset-config-key))
   (or enabled-agents [])))

#?(:clj
   (defn safe-probe
     "Run a plain server-side probe and return either a summarized value or error payload."
     [label f]
     (try
       {:label label
        :ok true
        :value (f)}
       (catch Throwable t
         {:label label
          :ok false
          :error {:class (str (class t))
                  :message (.getMessage t)}}))))

;; =========Client-side State=========
;; Keep this deliberately browser-local. These are preferences, not authority:
;; every remembered ID is checked against the server-provided options before it
;; can become effective, so a user who loses access cannot restore stale scope.
(def playground-preferences-storage-key "digdir.playground.preferences.v1")

(def playground-preference-keys
  [:selected-agent-id
   :selected-tenant
   :selected-dataset-config-key
   :show-sidebar])

(defn normalize-playground-preferences
  "Accept only the small, non-sensitive preference surface stored in the browser."
  [preferences]
  (let [preferences (if (map? preferences) preferences {})
        non-blank-string (fn [value]
                           (when (and (string? value) (not (str/blank? value)))
                             value))]
    (cond-> {}
      (non-blank-string (:selected-agent-id preferences))
      (assoc :selected-agent-id (non-blank-string (:selected-agent-id preferences)))

      (non-blank-string (:selected-tenant preferences))
      (assoc :selected-tenant (non-blank-string (:selected-tenant preferences)))

      (non-blank-string (:selected-dataset-config-key preferences))
      (assoc :selected-dataset-config-key
             (non-blank-string (:selected-dataset-config-key preferences)))

      (boolean? (:show-sidebar preferences))
      (assoc :show-sidebar (:show-sidebar preferences)))))

(defn playground-preferences
  "Project chat state onto the versioned, browser-persisted preference surface."
  [state]
  (normalize-playground-preferences (select-keys (or state {}) playground-preference-keys)))

(defn default-playground-chat-state
  "Fresh chat state, optionally hydrated with validated browser preferences."
  ([] (default-playground-chat-state nil))
  ([preferences]
   (merge
    {:conversation-id nil
     :selected-agent-id nil
     :selected-tenant nil
     :selected-dataset-config-key nil
     :query ""
     :config (default-chat-config)
     :execution-id nil
     :show-sidebar true
     :active-branch-path {}
     :editing-msg-id nil
     :editing-text ""
     :compare-mode false
     :compare-execution-ids []
     :reference-chunk-ids []
     :reference-index nil
     :scroll-to-message-id nil
     :pending-send nil
     :pending-regenerate nil
     :pending-edit-submit nil
     :sidebar-page-size 20
     :conversation-search ""
     :conversation-list-revision 0}
    (normalize-playground-preferences preferences))))

(defn preferred-option
  "Keep a valid remembered selection, otherwise choose the first available option."
  [selected-value available-values]
  (let [available-values (vec (remove str/blank? (or available-values [])))]
    (cond
      (some #{selected-value} available-values) selected-value
      (seq available-values) (first available-values)
      :else nil)))

(defn selected-reference
  "Return a safe, internally consistent citation selection for the side pane.
   Electric may propagate individual map fields at different instants, so this
   derives the vector, bounded index, and selected id in one pure step."
  [state]
  (let [ids (vec (or (:reference-chunk-ids state) []))
        requested-index (:reference-index state)]
    (when (and (number? requested-index) (seq ids))
      (let [index (min (max 0 requested-index) (dec (count ids)))]
        {:chunk-id (get ids index)
         :index index
         :total (count ids)}))))

(defn conversation-sidebar-state
  "Project the global Playground state onto the only client values that may
   affect the sidebar reactor. Message, execution, citation, and editor state
   are intentionally excluded so those updates cannot replace the pane."
  [state]
  {:conversation-id (:conversation-id state)
   :page-size (or (:sidebar-page-size state) 20)
   :search-query (or (:conversation-search state) "")
   :revision (or (:conversation-list-revision state) 0)})

#?(:cljs
   (defn- load-playground-preferences
     []
     (try
       (some-> (.getItem js/localStorage playground-preferences-storage-key)
               not-empty
               edn/read-string
               normalize-playground-preferences)
       (catch :default _
         {}))))

#?(:cljs
   (defn- persist-playground-preferences!
     [state]
     (try
       (.setItem js/localStorage
                 playground-preferences-storage-key
                 (pr-str (playground-preferences state)))
       (catch :default _
         nil))))

;; State for multi-message chat mode.
#?(:cljs
   (defonce !playground-chat-state
     (atom (default-playground-chat-state (load-playground-preferences)))))

#?(:cljs
   (defonce ^:private playground-preferences-watch-installed?
     (do
       (add-watch !playground-chat-state
                  ::persist-preferences
                  (fn [_ _ _ new-state]
                    (persist-playground-preferences! new-state)))
       true)))

;; =========Dataset-First Helper Aliases=========

(defn render-markdown-to-html [content]
  (common/render-markdown-to-html content))

(defn render-inline-markdown-to-html [content]
  (common/render-inline-markdown-to-html content))

(defn metadata->markdown [metadata]
  (common/metadata->markdown metadata))

(defn markdown-preview [content] (base/markdown-preview content))
(defn set-markdown-html! [node html] (base/set-markdown-html! node html))
(defn source-display-data [& args] (apply base/source-display-data args))
(defn citation-source-display-data [& args] (apply base/citation-source-display-data args))
(e/defn SourceTitleMarkdown [title style] (base/SourceTitleMarkdown title style))
(e/defn HeadingLineMarkdown [line style] (base/HeadingLineMarkdown line style))
(e/defn SourceTitleAndHeading [source ts hs] (base/SourceTitleAndHeading source ts hs))
(e/defn ChunkModalHeader [s c oc] (base/ChunkModalHeader s c oc))
(e/defn NextDetailedResponseView [message diagnostics dataset-config on-select-chunk]
  (observability/NextDetailedResponseView message diagnostics dataset-config on-select-chunk))
(defn agent-status-messages [diagnostics] (common/agent-status-messages diagnostics))
(e/defn DiagnosticsChunkModal [id d c oc] (base/DiagnosticsChunkModal id d c oc))
(e/defn DiagnosticsChunkPanel [id d c oc position total on-previous on-next]
  (base/DiagnosticsChunkPanel id d c oc position total on-previous on-next))
(defn normalize-debug-playground-mode [mode] (common/normalize-debug-playground-mode mode))

(def source-title-ellipsis-style base/source-title-ellipsis-style)
(def source-heading-ellipsis-style base/source-heading-ellipsis-style)
(def source-modal-title-style base/source-modal-title-style)
(def source-modal-heading-style base/source-modal-heading-style)

;; =========Multi-Message Chat Components=========

;; =========Shared Helper Aliases=========

(defn render-markdown-with-citations [content]
  (common/render-markdown-with-citations content))

(defn format-retrieval-filter-label [entry] (common/format-retrieval-filter-label entry))
(defn retrieval-filter-entries [diagnostics] (common/retrieval-filter-entries diagnostics))

(def citation-style styles/citation-style)

;; =========Branching Support=========

(declare MessageBubbleWithBranching)

(e/defn BranchSelector
  "Shows branch options when a message has multiple children."
  [parent-msg-id children active-branch-path on-branch-select]
  (e/client
   (when (> (count children) 1)
     (dom/div
      (dom/props {:style {:display "flex"
                          :gap "0.25rem"
                          :padding "0.25rem 0.5rem"
                          :margin "0.25rem 0"
                          :background "#fef3c7"
                          :border-radius "4px"
                          :align-items "center"
                          :font-size "0.75rem"}})
      (dom/span
       (dom/props {:style {:color "#92400e" :margin-right "0.25rem"}})
       (dom/text (t :playground/branches)))
      (let [selected-idx (get active-branch-path parent-msg-id 0)]
        (e/for-by identity [idx (range (count children))]
          (dom/button
           (dom/props {:style {:padding "0.125rem 0.5rem"
                               :background (if (= idx selected-idx) "#3b82f6" "#e5e7eb")
                               :color (if (= idx selected-idx) "white" "#374151")
                               :border "none"
                               :border-radius "3px"
                               :cursor "pointer"
                               :font-size "0.7rem"}})
           (dom/text (str (inc idx)))
           (let [[t err] (e/Token (dom/On "click" identity nil))]
             (when t
               (on-branch-select parent-msg-id idx)
               (t))))))))))

(e/defn MessageBubbleWithBranching
  "Message bubble with branching support, edit, regenerate, and source navigation."
  [{:keys [message
           dataset-config
           all-messages
           on-edit
           on-regenerate
           active-branch-path
           on-branch-select
           on-select-reference
           scroll-into-view?
           on-scrolled]}]
  (e/client
   (let [is-user (= :user (:message/role message))
         msg-id (:message/id message)
         parent-id (get-in message [:message/parent-message :message/id])
         diagnostics-str (:message/diagnostics message)
         config-str (:message/config message)
         config (when config-str (e/server (edn/read-string config-str)))
         ;; Parse diagnostics once on server
         parsed-diagnostics (when diagnostics-str
                              (e/server (edn/read-string diagnostics-str)))
         citation-chunk-ids (->> (:citations parsed-diagnostics)
                                 (sort-by :index)
                                 (keep :chunk-id)
                                 distinct
                                 vec)
         ;; Check if this message's parent has multiple children (branch point)
         siblings (chat-session/find-children all-messages parent-id)
         is-branch-point (> (count siblings) 1)
         ;; Check if this message has multiple children
         children (chat-session/find-children all-messages msg-id)
         has-child-branches (> (count children) 1)
         on-select-chunk (fn [chunk-id]
                           (on-select-reference
                            {:chunk-id chunk-id
                             :chunk-ids (if (some #{chunk-id} citation-chunk-ids)
                                          citation-chunk-ids
                                          [chunk-id])}))
         clarification-request (:clarification-request parsed-diagnostics)
         needs-clarification? (= :needs_clarification (:status parsed-diagnostics))
         !clarification-reply (atom "")
         clarification-reply (e/watch !clarification-reply)]
     (dom/div
      (when scroll-into-view?
        (let [node dom/node]
          (js/requestAnimationFrame
           (fn []
             (.scrollIntoView node true)
             (on-scrolled)))))
      ;; Show branch selector before this message if parent has multiple children
      (when (and is-branch-point is-user)
        (BranchSelector parent-id siblings active-branch-path on-branch-select))

      (dom/div
       (dom/props {:style {:display "flex"
                           :flex-direction "column"
                           :align-items (if is-user "flex-end" "flex-start")}})

       ;; Message content - view mode for assistant, plain for user
       (dom/div
        (dom/props {:style (if is-user assistant-message-style assistant-message-style)}) ;; wait, user-message-style?
        (if is-user
          (dom/div
           (dom/props {:style user-message-style})
           (dom/text (:message/text message)))
          ;; Assistant message: render based on view mode
          (if needs-clarification?
            (dom/div
             (dom/div
              (dom/props {:style {:display "inline-flex"
                                  :align-items "center"
                                  :margin-bottom "0.5rem"
                                  :padding "0.2rem 0.45rem"
                                  :border-radius "999px"
                                  :background "#fef3c7"
                                  :color "#92400e"
                                  :font-size "0.7rem"
                                  :font-weight "600"
                                  :letter-spacing "0.01em"}})
              (dom/text (t :playground/question)))
             (let [html (e/server (base/render-markdown-to-html (:message/text (e/client message))))]
               (dom/div
                (dom/props {:style {:line-height "1.6"
                                    :margin-bottom "0.75rem"}})
                (when html
                  (base/set-markdown-html! dom/node html))))
             (when (seq (:options clarification-request))
               (dom/div
                (dom/props {:style {:display "flex"
                                    :gap "0.5rem"
                                    :flex-wrap "wrap"
                                    :margin-bottom "0.75rem"}})
                (e/for-by identity [option (:options clarification-request)]
                  (dom/button
                   (dom/props {:style {:padding "0.35rem 0.65rem"
                                       :background "#fff7ed"
                                       :border "1px solid #fdba74"
                                       :border-radius "999px"
                                       :color "#9a3412"
                                       :cursor "pointer"
                                       :font-size "0.75rem"}})
                   (dom/text option)
                   (let [[t err] (e/Token (dom/On "click" identity nil))]
                     (when t
                       (swap! !playground-chat-state assoc
                              :pending-send {:query option
                                             :parent-msg-id msg-id
                                             :branch-index (count children)})
                       (t)))))))
             (dom/div
              (dom/props {:style {:display "flex"
                                  :gap "0.5rem"
                                  :align-items "flex-start"}})
              (dom/textarea
               (dom/props {:style (merge textarea-style {:flex "1"
                                                         :height "56px"
                                                         :resize "vertical"})
                           :placeholder (t :playground/answer-question)
                           :value clarification-reply})
               (dom/On "input" #(reset! !clarification-reply (.. % -target -value)) nil)
               (dom/On "keydown"
                       #(when (and (= "Enter" (.-key %))
                                   (not (.-shiftKey %))
                                   (not (str/blank? clarification-reply)))
                          (.preventDefault %)
                          (swap! !playground-chat-state assoc
                                 :pending-send {:query clarification-reply
                                                :parent-msg-id msg-id
                                                :branch-index (count children)})
                          (reset! !clarification-reply ""))
                       nil))
              (dom/button
               (dom/props {:style {:padding "0.5rem 0.85rem"
                                   :background (if (str/blank? clarification-reply) "#9ca3af" "#d97706")
                                   :color "white"
                                   :border "none"
                                   :border-radius "6px"
                                   :font-weight "500"
                                   :cursor (if (str/blank? clarification-reply) "not-allowed" "pointer")}
                           :disabled (str/blank? clarification-reply)})
               (dom/text (t :playground/reply))
               (when-not (str/blank? clarification-reply)
                 (let [[t err] (e/Token (dom/On "click" identity nil))]
                   (when t
                     (swap! !playground-chat-state assoc
                            :pending-send {:query clarification-reply
                                           :parent-msg-id msg-id
                                           :branch-index (count children)})
                     (reset! !clarification-reply "")
                     (t)))))))
            (if (and parsed-diagnostics
                   (or (seq (:citations parsed-diagnostics))
                       (seq (:agent-trace parsed-diagnostics))
                       (seq (:search-history parsed-diagnostics))
                       (seq (:used-chunks parsed-diagnostics))
                       ;; UI.4 — graph-variant agents (self-improve,
                       ;; etc.) don't emit :agent-trace because they
                       ;; aren't ReAct-shaped. But their diagnostics
                       ;; still carry timing + execution metadata that
                       ;; the post-run shell can render as collapsed
                       ;; header chips. Without this clause they fall
                       ;; through to plain markdown and lose the panel
                       ;; entirely after completion.
                       (seq (:agent-stage-timings parsed-diagnostics))
                       (seq (:execution-stage-timings parsed-diagnostics))
                       (:skill-execution-metadata parsed-diagnostics)
                       (:report-structured parsed-diagnostics)))
            ;; Detailed is the single Playground response view.
            (NextDetailedResponseView message parsed-diagnostics dataset-config
                                      on-select-chunk)
            ;; No diagnostic data — render plain markdown (backwards compat)
            (let [html (e/server (base/render-markdown-to-html (:message/text (e/client message))))]
              (dom/div
               (dom/props {:style {:line-height "1.6"}})
              (when html
                (base/set-markdown-html! dom/node html))))))))

       ;; Action row
       (dom/div
        (dom/props {:style {:display "flex"
                            :gap "0.5rem"
                            :margin-top "0.25rem"
                            :align-items "center"}})

        ;; Edit button (for user messages)
        (when is-user
          (dom/button
           (dom/props {:style {:background "none"
                               :border "1px solid #d1d5db"
                               :border-radius "4px"
                               :padding "0.25rem 0.5rem"
                               :font-size "0.7rem"
                               :cursor "pointer"
                               :color "#6b7280"}})
           (dom/text (t :playground/edit))
           (let [[t err] (e/Token (dom/On "click" identity nil))]
             (when t
               (on-edit msg-id (:message/text message))
               (t)))))

        ;; Regenerate button (for assistant messages)
        (when (and (not is-user) diagnostics-str (not needs-clarification?))
          (dom/button
           (dom/props {:style {:background "none"
                               :border "1px solid #d1d5db"
                               :border-radius "4px"
                               :padding "0.25rem 0.5rem"
                               :font-size "0.7rem"
                               :cursor "pointer"
                               :color "#6b7280"}})
           (dom/text (t :playground/regenerate))
           (let [[t err] (e/Token (dom/On "click" identity nil))]
             (when t
               (on-regenerate parent-id)
               (t)))))

        ;; Config badge if non-default
        (when (and config (:model config))
          (dom/span
           (dom/props {:style {:font-size "0.65rem"
                               :color "#9ca3af"
                               :padding "0.125rem 0.375rem"
                               :background "#f3f4f6"
                               :border-radius "3px"}})
           (dom/text (:model config))))

        ;; Branch indicator
        (when has-child-branches
          (dom/span
           (dom/props {:style {:font-size "0.65rem"
                               :color "#92400e"
                               :padding "0.125rem 0.375rem"
                               :background "#fef3c7"
                               :border-radius "3px"}})
           (dom/text (t :playground/branches-count (count children))))))

       )))))

(e/defn MessageThread
  "Scrollable list of messages."
  [{:keys [messages
           dataset-config
           dataset-stats
           scope-complete?
           on-edit
           on-regenerate
           active-branch-path
           on-branch-select
           on-select-reference
           scroll-to-message-id
           on-scrolled]}]
  (e/client
   (dom/div
    (dom/props {:style (assoc message-thread-style :scroll-behavior "smooth")})
    (if (empty? messages)
      (dom/div
       (dom/props {:style {:align-self "center"
                           :max-width "36rem"
                           :margin "auto"
                           :padding "2rem"
                           :text-align "center"
                           :border "1px solid #e5e7eb"
                           :border-radius "12px"
                           :background (if scope-complete? "#f8fafc" "#fffbeb")}})
       (dom/div
        (dom/props {:style {:font-size "2rem" :margin-bottom "0.75rem"}
                    :aria-hidden "true"})
        (dom/text (if scope-complete? "✦" "1 · 2 · 3")))
       (dom/h3
        (dom/props {:style {:margin "0 0 0.5rem" :font-size "1.1rem"
                            :color "#1f2937"}})
        (dom/text (t (if scope-complete?
                       :playground/empty-ready-title
                       :playground/empty-setup-title))))
       (dom/p
        (dom/props {:style {:margin "0" :font-size "0.875rem"
                            :line-height "1.6" :color "#6b7280"}})
        (dom/text (t (if scope-complete?
                       :playground/empty-ready-body
                       :playground/empty-setup-body))))
       (when (and scope-complete?
                  (some number? (vals (or dataset-stats {}))))
         (dom/div
          (dom/props {:style {:display "flex"
                              :justify-content "center"
                              :gap "0.75rem"
                              :flex-wrap "wrap"
                              :margin-top "1.25rem"}})
          (e/for [[label value]
                  (e/diff-by first
                             (filter (comp number? second)
                                     [[(t :playground/documents) (:documents dataset-stats)]
                                      [(t :playground/chunks) (:chunks dataset-stats)]
                                      [(t :playground/phrases) (:phrases dataset-stats)]]))]
            (dom/div
             (dom/props {:style {:min-width "7rem"
                                 :padding "0.65rem 0.85rem"
                                 :background "white"
                                 :border "1px solid #dbeafe"
                                 :border-radius "8px"}})
             (dom/div
              (dom/props {:style {:font-size "1.15rem"
                                  :font-weight "700"
                                  :color "#1d4ed8"}})
              (dom/text value))
             (dom/div
              (dom/props {:style {:font-size "0.7rem"
                                  :color "#64748b"
                                  :margin-top "0.15rem"}})
              (dom/text label)))))))
      (e/for [msg (e/diff-by :message/id messages)]
        (MessageBubbleWithBranching
         {:message                msg
          :dataset-config         dataset-config
          :all-messages           messages
          :on-edit                on-edit
          :on-regenerate          on-regenerate
          :active-branch-path     active-branch-path
          :on-branch-select       on-branch-select
          :on-select-reference    on-select-reference
          :scroll-into-view?      (= (:message/id msg) scroll-to-message-id)
          :on-scrolled            on-scrolled}))))))

(e/defn EditMessageModal
  "Modal for editing a message and creating a branch."
  [editing-msg-id editing-text on-cancel on-submit]
  (e/client
   (when editing-msg-id
     (dom/div
      (dom/props {:style modal-backdrop-style})
      (dom/div
       (dom/props {:style (merge modal-content-style {:max-width "600px"})})
       ;; Header
       (dom/div
        (dom/props {:style {:display "flex"
                            :justify-content "space-between"
                            :align-items "center"
                            :margin-bottom "1rem"}})
        (dom/h3
         (dom/props {:style {:margin "0" :font-size "1rem" :font-weight "600"}})
         (dom/text (t :playground/edit-message)))
        (dom/button
         (dom/props {:style {:background "none"
                             :border "none"
                             :font-size "1.25rem"
                             :cursor "pointer"
                             :color "#6b7280"}})
         (dom/text "×")
         (let [[t err] (e/Token (dom/On "click" identity nil))]
           (when t
             (on-cancel)
             (t)))))
       ;; Textarea
       (dom/textarea
        (dom/props {:style (merge textarea-style {:height "120px" :margin-bottom "1rem"})
                    :value editing-text
                    :autofocus true})
        (dom/On "input" #(swap! !playground-chat-state assoc :editing-text (.. % -target -value)) nil))
       ;; Buttons
       (dom/div
        (dom/props {:style {:display "flex" :gap "0.5rem" :justify-content "flex-end"}})
        (dom/button
         (dom/props {:style {:padding "0.5rem 1rem"
                             :background "#e5e7eb"
                             :border "none"
                             :border-radius "6px"
                             :cursor "pointer"}})
         (dom/text (t :playground/cancel))
         (let [[t err] (e/Token (dom/On "click" identity nil))]
           (when t
             (on-cancel)
             (t))))
        (dom/button
         (dom/props {:style {:padding "0.5rem 1rem"
                             :background "#3b82f6"
                             :color "white"
                             :border "none"
                             :border-radius "6px"
                             :cursor "pointer"}})
         (dom/text (t :playground/send-as-branch))
         (let [[t err] (e/Token (dom/On "click" identity nil))]
           (when t
             (on-submit editing-text)
             (t))))))))))

(e/defn CompareRunsPanel
  "Side-by-side comparison of two execution runs."
  [execution-ids on-close]
  (e/client
   (let [executions (e/server
                     (mapv #(get @playground/!playground-executions %) (e/client execution-ids)))]
     (dom/div
      (dom/props {:style modal-backdrop-style})
      (dom/div
       (dom/props {:style (merge modal-content-style {:max-width "1200px"
                                                      :width     "95%"})})
       ;; Header
       (dom/div
        (dom/props {:style {:display         "flex"
                            :justify-content "space-between"
                            :align-items     "center"
                            :margin-bottom   "1rem"}})
        (dom/h3
         (dom/props {:style {:margin      "0"
                             :font-size   "1rem"
                             :font-weight "600"}})
         (dom/text (t :playground/compare-runs)))
        (dom/button
         (dom/props {:style {:background "none"
                             :border     "none"
                             :font-size  "1.25rem"
                             :cursor     "pointer"
                             :color      "#6b7280"}})
         (dom/text "×")
         (let [[t err] (e/Token (dom/On "click" identity nil))]
           (when t
             (on-close)
             (t)))))
       ;; Comparison grid
       (dom/div
        (dom/props {:style {:display               "grid"
                            :grid-template-columns "1fr 1fr"
                            :gap                   "1rem"}})
        (e/for-by identity [idx (range (min 2 (count executions)))]
                  (let [exec (nth executions idx nil)]
                    (dom/div
                     (dom/props {:style {:border        "1px solid #e5e7eb"
                                         :border-radius "6px"
                                         :padding       "1rem"}})
                     (dom/div
                      (dom/props {:style {:font-weight    "600"
                                          :margin-bottom  "0.75rem"
                                          :padding-bottom "0.5rem"
                                          :border-bottom  "1px solid #e5e7eb"}})
                      (dom/text (t :playground/run-label (inc idx))))
                     (if exec
                       (dom/div
                ;; Query
                        (dom/div
                         (dom/props {:style {:margin-bottom "0.5rem"}})
                         (dom/span (dom/props {:style {:font-weight "500"
                                                       :color       "#6b7280"}}) (dom/text (t :playground/query-label)))
                         (dom/span (dom/text (or (:query exec) "N/A"))))
                ;; Status
                        (dom/div
                         (dom/props {:style {:margin-bottom "0.5rem"}})
                         (dom/span (dom/props {:style {:font-weight "500"
                                                       :color       "#6b7280"}}) (dom/text (t :playground/status-label)))
                         (dom/span (dom/text (name (or (:status exec) :unknown)))))
                ;; Search phrases
                        (dom/div
                         (dom/props {:style {:margin-bottom "0.5rem"}})
                         (dom/span (dom/props {:style {:font-weight "500"
                                                       :color       "#6b7280"}}) (dom/text (t :playground/search-phrases-label)))
                         (dom/span (dom/text (str (count (get-in exec [:results :query-relaxation]))))))
                ;; Chunks used
                        (dom/div
                         (dom/props {:style {:margin-bottom "0.5rem"}})
                         (dom/span (dom/props {:style {:font-weight "500"
                                                       :color       "#6b7280"}}) (dom/text (t :playground/chunks-used-label)))
                         (dom/span (dom/text (str (count (get-in exec [:results :used-chunks]))))))
                ;; Response preview
                        (dom/div
                         (dom/props {:style {:margin-top "0.75rem"}})
                         (dom/div
                          (dom/props {:style {:font-weight   "500"
                                              :color         "#6b7280"
                                              :margin-bottom "0.25rem"}})
                          (dom/text (t :playground/response-label)))
                         (dom/div
                          (dom/props {:style {:background    "#f9fafb"
                                              :padding       "0.5rem"
                                              :border-radius "4px"
                                              :max-height    "200px"
                                              :overflow-y    "auto"
                                              :font-size     "0.8rem"}})
                          (let [content (or (:streaming-content exec) (t :playground/no-response))
                                html    (e/server (base/render-markdown-to-html content))]
                           (when html
                              (base/set-markdown-html! dom/node html))))))
                       (dom/div
                        (dom/props {:style {:color "#9ca3af"}})
                        (dom/text (t :playground/no-execution-data)))))))))))))

(e/defn ConversationSidebar
  "Sidebar listing playground conversations."
  [conversations current-convo-id on-select-convo on-delete-convo on-clear-all
   agent-names total-count search-query on-search-change has-more? on-load-older]
  (e/client
   (dom/div
    (dom/props {:style conversation-sidebar-style})
    ;; Header with Clear all button
    (dom/div
     (dom/props {:style {:padding       "0.75rem 1rem"
                         :border-bottom "1px solid #e5e7eb"
                         :display       "flex"
                         :justify-content "space-between"
                         :align-items   "center"}})
     (dom/span
      (dom/props {:style {:font-weight "600"
                          :font-size   "0.875rem"
                          :color       "#374151"}})
      (dom/text (t :playground/conversations)))
     (when (pos? (or total-count 0))
       (dom/button
        (dom/props {:style {:padding       "0.25rem 0.5rem"
                            :background    "transparent"
                            :border        "1px solid #fecaca"
                            :border-radius "4px"
                            :font-size     "0.7rem"
                            :color         "#dc2626"
                            :cursor        "pointer"}})
        (dom/text (t :playground/clear-all))
        (let [[t err] (e/Token (dom/On "click" identity nil))]
          (when t
            (on-clear-all)
            (t))))))
    (dom/div
     (dom/props {:style {:padding "0.65rem 0.75rem"
                         :border-bottom "1px solid #e5e7eb"}})
     (dom/input
      (dom/props {:type "search"
                  :aria-label (t :playground/search-conversations)
                  :placeholder (t :playground/search-conversations)
                  :value (or search-query "")
                  :style (merge input-style
                                {:width "100%"
                                 :font-size "0.78rem"
                                 :padding "0.45rem 0.6rem"})})
      (dom/On "input" #(on-search-change (.. % -target -value)) nil)))
    ;; Conversation list
    (dom/div
     (dom/props {:style {:flex "1" :overflow-y "auto"}})
     (if (empty? conversations)
       (dom/div
        (dom/props {:style {:padding "1rem" :color "#9ca3af" :font-size "0.8rem"}})
        (dom/text (t (if (str/blank? (or search-query ""))
                       :playground/no-conversations
                       :playground/no-conversation-results))))
       (e/for [conv (e/diff-by :conversation/id conversations)]
         (let [conv-id (:conversation/id conv)
               is-selected (= conv-id current-convo-id)]
           (dom/div
            (dom/props {:style (merge sidebar-item-style
                                      {:display     "flex"
                                       :align-items "flex-start"
                                       :gap         "0.5rem"}
                                      (when is-selected
                                        {:background "#dbeafe"}))})
          ;; Conversation info (clickable)
          (dom/div
           (dom/props {:style {:flex   "1"
                               :cursor "pointer"}})
           (dom/On "click" (fn [_] (on-select-convo conv)) nil)
           (dom/div
            (dom/props {:style {:font-weight "500" :margin-bottom "0.25rem"}})
            (dom/text (or (:conversation/topic conv) (t :playground/untitled))))
           (dom/div
            (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
            (dom/text (conversation-scope-label conv agent-names))))
          ;; Delete button
          (dom/button
           (dom/props {:style {:padding    "0.25rem"
                               :background "transparent"
                               :border     "none"
                               :color      "#9ca3af"
                               :cursor     "pointer"
                               :font-size  "0.875rem"}
                       :aria-label (t :playground/delete-conversation)
                       :title (t :playground/delete-conversation)})
           (dom/text "×")
           (let [[t err] (e/Token (dom/On "click" identity nil))]
             (when t
               (on-delete-convo conv-id)
               (t))))))))
     (when has-more?
       (dom/button
        (dom/props {:style {:margin        "0.5rem 0.75rem 0.75rem"
                            :padding       "0.4rem 0.75rem"
                            :background    "transparent"
                            :border        "1px solid #d1d5db"
                            :border-radius "4px"
                            :font-size     "0.75rem"
                            :color         "#374151"
                            :cursor        "pointer"
                            :width         "calc(100% - 1.5rem)"}})
        (dom/text (t :playground/load-older))
        (let [[t err] (e/Token (dom/On "click" identity nil))]
          (when t
            (on-load-older)
            (t)))))))))

(e/defn ConversationSidebarPage
  "Fetch only when an actual sidebar-query input changes. Keeping this in its
   own reactor prevents message streaming and other Playground state updates
   from revoking the sidebar's server result."
  [user-id page-size search-query revision]
  (e/server
   (let [uid (e/client user-id)
         limit (e/client page-size)
         query (e/client search-query)
         refresh-revision (e/client revision)]
     (if uid
       (e/Offload
        #(let [result (db/playground-conversation-sidebar-page
                       @(db/get-conn)
                       uid
                       {:limit limit
                        :query query})]
           (timbre/info :playground/fetch-sidebar
                        {:user-id uid
                         :limit limit
                         :query query
                         :revision refresh-revision
                         :returned (count (:items result))
                         :total (:total-count result)})
           result))
       {:items [] :total-count 0 :user-total-count 0}))))

(e/defn PlaygroundConversationSidebarRegion
  "Stable sibling reactor for the conversations pane. Its database work and
   DOM lifetime are independent from the message/execution pane."
  [conversation-id page-size search-query revision]
  (e/client
   (let [user-id (e/server (auth/current-user-id e/http-request))
         enabled-agents (e/server
                         (let [conn (config-db/get-conn)]
                           (if conn (agents-db/list-enabled-agents @conn) [])))
         agent-names (into {} (map (juxt :id :name) enabled-agents))
         sidebar-page (ConversationSidebarPage
                       user-id page-size search-query revision)
         conversations (:items sidebar-page)
         total-count (:total-count sidebar-page)
         user-total-count (:user-total-count sidebar-page)
         has-more? (> (or total-count 0) page-size)]
     (ConversationSidebar
      conversations conversation-id
      (fn [conv]
        (swap! !playground-chat-state assoc
               :conversation-id (:conversation/id conv)
               :selected-agent-id (:conversation/agent-id conv)
               :selected-tenant (:conversation/tenant conv)
               :selected-dataset-config-key
               (:dataset-config-key (conversation-dataset-scope conv))
               :active-branch-path {}
               :reference-chunk-ids []
               :reference-index nil
               :pending-load-execution-for-convo (:conversation/id conv)))
      (fn [convo-id]
        (swap! !playground-chat-state assoc :pending-delete-convo-id convo-id))
      (fn []
        (swap! !playground-chat-state assoc :pending-clear-all true))
      agent-names
      user-total-count
      search-query
      (fn [query]
        (swap! !playground-chat-state assoc
               :conversation-search query
               :sidebar-page-size 20))
      has-more?
      (fn []
        (swap! !playground-chat-state update :sidebar-page-size
               (fn [n] (+ (or n 20) 20))))))))

(e/defn PlaygroundChatInput
  "Chat input area with send button."
  [!query !config _dataset-config-key is-running on-send]
  (e/client
   (let [query (e/watch !query)]
     (dom/div
      (dom/props {:style chat-input-style})
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem"}})
       (dom/textarea
        (dom/props {:style (merge textarea-style {:flex "1" :height "60px" :resize "none"})
                    :placeholder (t :playground/message-placeholder)
                    :value query})
        (dom/On "input" #(reset! !query (.. % -target -value)) nil)
        (dom/On "keydown" #(when (and (= "Enter" (.-key %))
                                      (not (.-shiftKey %))
                                      (not (str/blank? query))
                                      (not is-running))
                            (.preventDefault %)
                            (on-send)) nil))
       (dom/button
        (dom/props {:style {:padding "0.5rem 1rem"
                            :background (if (and (not (str/blank? query))
                                                 (not is-running))
                                          "#3b82f6" "#9ca3af")
                            :color "white"
                            :border "none"
                            :border-radius "6px"
                            :font-weight "500"
                            :cursor (if (and (not (str/blank? query))
                                             (not is-running))
                                      "pointer" "not-allowed")}
                    :disabled (or (str/blank? query) is-running)})
        (dom/text (if is-running "Sending..." "Send"))
        (when (and (not (str/blank? query)) (not is-running))
          (let [[t err] (e/Token (dom/On "click" identity nil))]
            (when t
              (on-send)
              (t))))))))))

(e/defn PlaygroundScopeHeader
  "Header and scope selectors for the playground chat surface."
  [show-sidebar?
   selected-agent-id
   effective-agent-id
   enabled-agents
   effective-selected-tenant
   visible-tenants
   tenant-names
   effective-selected-dataset-config-key
   visible-dataset-scope-options
   resolution-level
   scope-complete?
   config
   on-toggle-sidebar
   on-agent-change
   on-tenant-change
   on-dataset-scope-change
   on-config-change
   on-new-conversation]
  (e/client
   (let [selector-agent-id (displayed-agent-id selected-agent-id effective-agent-id enabled-agents)
         normalized-dataset-scope-options (mapv normalize-dataset-scope-option
                                                (or visible-dataset-scope-options []))
         model-value (or (:model config) "")
         models (vec (distinct (or (seq (:available-models config))
                                   ["gpt-4o" "gpt-4o-mini" "gpt-5.4-mini"
                                    "gpt-5.6-sol" "gpt-4"])))]
     (dom/div
      (dom/props {:style {:padding         "0.75rem 1rem"
                          :border-bottom   "1px solid #e5e7eb"
                          :display         "flex"
                          :gap             "1rem"
                          :justify-content "space-between"
                          :align-items     "flex-start"
                          :flex-wrap       "wrap"}})
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.75rem" :align-items "center"
                           :padding-top "1.35rem"}})
       (dom/button
        (dom/props {:style {:background    "none"
                            :border        "1px solid #d1d5db"
                            :border-radius "4px"
                            :padding       "0.25rem 0.5rem"
                            :cursor        "pointer"}
                    :aria-label (t (if show-sidebar?
                                     :playground/hide-conversations
                                     :playground/show-conversations))
                    :title (t (if show-sidebar?
                                :playground/hide-conversations
                                :playground/show-conversations))})
        (dom/text (if show-sidebar? "◀" "▶"))
        (let [[t err] (e/Token (dom/On "click" identity nil))]
          (when t
            (on-toggle-sidebar)
            (t))))
       (dom/h2
        (dom/props {:style {:margin "0" :font-size "1.125rem" :font-weight "600"}})
        (dom/text (t :playground/chat-header))))

      (dom/div
       (dom/props {:style {:display "flex" :flex-direction "column"
                           :align-items "flex-end" :gap "0.35rem"}})
       (dom/div
        (dom/props {:style {:display "flex" :gap "0.5rem" :align-items "flex-end"
                            :flex-wrap "wrap" :justify-content "flex-end"}})

        ;; Required scope follows the natural narrowing order: organization,
        ;; dataset, agent, then the model used for the run.
        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.2rem"}})
         (dom/label
          (dom/props {:for "playground-tenant" :style (merge label-style {:margin-bottom "0"})})
          (dom/text (t :playground/tenant-label)))
         (dom/select
          (dom/props {:id "playground-tenant"
                      :aria-label (t :playground/tenant-label)
                      :style (merge select-style {:width "180px"}
                                    (when (not (str/blank? effective-selected-tenant))
                                      {:border-color "#3b82f6" :background "#eff6ff"}))
                      :disabled (empty? visible-tenants)
                      :value (or effective-selected-tenant "")})
          (if (seq visible-tenants)
            (e/for [tenant (e/diff-by identity visible-tenants)]
              (dom/option
               (dom/props {:value tenant
                           :selected (= tenant effective-selected-tenant)})
               (dom/text (clojure.core/get tenant-names tenant tenant))))
            (dom/option (dom/props {:value "" :selected true})
                        (dom/text (t :playground/no-tenants))))
          (dom/On "change" #(on-tenant-change (.. % -target -value)) nil)))

        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.2rem"}})
         (dom/label
          (dom/props {:for "playground-dataset" :style (merge label-style {:margin-bottom "0"})})
          (dom/text (t :playground/dataset-label)))
         (dom/select
          (dom/props {:id "playground-dataset"
                      :aria-label (t :playground/dataset-label)
                      :style (merge select-style {:width "220px"}
                                    (when (not (str/blank? effective-selected-dataset-config-key))
                                      {:border-color "#3b82f6" :background "#eff6ff"}))
                      :disabled (empty? normalized-dataset-scope-options)
                      :value (or effective-selected-dataset-config-key "")})
          (if (seq normalized-dataset-scope-options)
            (e/for [dataset-scope-option (e/diff-by :value normalized-dataset-scope-options)]
              (dom/option
               (dom/props {:value (:value dataset-scope-option)
                           :selected (= (:value dataset-scope-option)
                                        effective-selected-dataset-config-key)})
               (dom/text (:label dataset-scope-option))))
            (dom/option (dom/props {:value "" :selected true})
                        (dom/text (t :playground/no-datasets))))
          (dom/On "change" #(on-dataset-scope-change (.. % -target -value)) nil)))

        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.2rem"}})
         (dom/label
          (dom/props {:for "playground-agent" :style (merge label-style {:margin-bottom "0"})})
          (dom/text (t :playground/agent-label)))
         (dom/select
          (dom/props {:id "playground-agent"
                      :aria-label (t :playground/agent-label)
                      :style (merge select-style {:width "220px"}
                                    (when (not (str/blank? selector-agent-id))
                                      {:border-color "#3b82f6" :background "#eff6ff"}))
                      :disabled (empty? enabled-agents)
                      :value (or selector-agent-id "")})
          (if (seq enabled-agents)
            (e/for [agent (e/diff-by :id enabled-agents)]
              (dom/option
               (dom/props {:value (:id agent)
                           :selected (= (:id agent) selector-agent-id)})
               (dom/text (:name agent))))
            (dom/option (dom/props {:value "" :selected true})
                        (dom/text (t :playground/no-agents))))
          (dom/On "change" #(on-agent-change (.. % -target -value)) nil)))

        (dom/div
         (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.2rem"}})
         (dom/label
          (dom/props {:for "playground-model" :style (merge label-style {:margin-bottom "0"})})
          (dom/text (t :playground/model-label)))
         (dom/select
          (dom/props {:id "playground-model"
                      :aria-label (t :playground/model-label)
                      :style (merge select-style {:width "220px"
                                                  :border-color "#3b82f6"
                                                  :background "#eff6ff"})
                      :value model-value})
          (dom/option
           (dom/props {:value "" :selected (str/blank? model-value)})
           (dom/text (if (:default-model config)
                       (str (t :playground/default-model) " (" (:default-model config) ")")
                       (t :playground/default-model))))
          (e/for [model (e/diff-by identity models)]
            (dom/option
             (dom/props {:value model :selected (= model model-value)})
             (dom/text model)))
          (dom/On "change"
                  #(let [value (.. % -target -value)]
                     (on-config-change
                      (assoc config :model (when-not (str/blank? value) value))))
                  nil)))

        (dom/span
         (dom/props {:style {:font-size "0.75rem" :font-weight "600"
                             :padding "0.45rem 0.65rem" :border-radius "999px"
                             :margin-bottom "0.15rem"
                             :background (if scope-complete? "#dcfce7" "#fef3c7")
                             :color (if scope-complete? "#166534" "#92400e")}})
         (dom/text
          (if scope-complete?
            (t :playground/ready-to-chat)
            (case resolution-level
              2 (t :playground/choose-dataset)
              3 (t :playground/choose-tenant)
              4 (t :playground/choose-tenant)
              (t :playground/choose-agent)))))

        (dom/button
         (dom/props {:style {:padding "0.5rem 1rem"
                             :margin-bottom "0.05rem"
                             :background (if scope-complete? "#10b981" "#9ca3af")
                             :color "white" :border "none" :border-radius "6px"
                             :font-weight "500"
                             :cursor (if scope-complete? "pointer" "not-allowed")}
                     :disabled (not scope-complete?)})
         (dom/text (t :playground/new-chat))
         (when scope-complete?
           (let [[tok err] (e/Token (dom/On "click" identity nil))]
             (when tok
               (on-new-conversation)
               (tok)))))

       (dom/div
        (dom/props {:style {:font-size "0.75rem" :color "#6b7280"}})
        (dom/text (t :playground/preferences-remembered)))))))))

(e/defn PlaygroundConversationPane
  "Main chat pane once the current state and server data have been resolved."
 [state
   conversation-id
   effective-agent-id
   enabled-agents
   effective-selected-tenant
   visible-tenants
   tenant-names
   effective-selected-dataset-config-key
   visible-dataset-scope-options
   resolution-level
   scope-complete?
   selected-dataset-config
   dataset-stats
   visible-messages
   all-messages
   active-branch-path
   stream-view
   is-running
   config
   last-msg-id
   editing-msg-id
   editing-text
   compare-mode
   compare-execution-ids]  (e/client
   (let [on-edit-message
         (fn [msg-id text]
           (swap! !playground-chat-state assoc
                  :editing-msg-id msg-id
                  :editing-text text))
         on-regenerate-message
         (fn [parent-user-msg-id]
           (when parent-user-msg-id
             (let [user-msg (first (filter #(= parent-user-msg-id (:message/id %)) all-messages))]
               (when user-msg
                 (let [user-parent-id (get-in user-msg [:message/parent-message :message/id])
                       siblings       (chat-session/find-children all-messages user-parent-id)
                       new-branch-idx (count siblings)]
                   (swap! !playground-chat-state assoc
                          :pending-regenerate {:parent-id  user-parent-id
                                               :query      (:message/text user-msg)
                                               :branch-idx new-branch-idx}))))))
         on-branch-select-message
         (fn [parent-id idx]
           (swap! !playground-chat-state assoc-in [:active-branch-path parent-id] idx))]
     (dom/div
      (dom/props {:style {:flex           "1"
                          :display        "flex"
                          :flex-direction "column"
                          :min-width      "0"}})
      (PlaygroundScopeHeader
       (:show-sidebar state)
       (:selected-agent-id state)
       effective-agent-id
       enabled-agents
       effective-selected-tenant
       visible-tenants
       tenant-names
       effective-selected-dataset-config-key
       visible-dataset-scope-options
       resolution-level
       scope-complete?
       config
       #(swap! !playground-chat-state update :show-sidebar not)
       #(swap! !playground-chat-state assoc
               :selected-agent-id %)
       #(swap! !playground-chat-state assoc
               :selected-tenant %
               :selected-dataset-config-key nil
               :selected-agent-id nil)
       #(swap! !playground-chat-state assoc
               :selected-dataset-config-key %
               :selected-agent-id nil)
       #(swap! !playground-chat-state assoc :config %)
       #(swap! !playground-chat-state assoc :pending-new-conversation true))
      (MessageThread
      {:messages               visible-messages
       :dataset-config         selected-dataset-config
        :dataset-stats          dataset-stats
        :scope-complete?        scope-complete?
        :on-edit                on-edit-message
        :on-regenerate          on-regenerate-message
        :active-branch-path     active-branch-path
        :on-branch-select       on-branch-select-message
        :on-select-reference    (fn [{:keys [chunk-id chunk-ids]}]
                                  (let [ids (vec (or (seq chunk-ids) [chunk-id]))
                                        idx (or (first (keep-indexed
                                                       (fn [i id]
                                                         (when (= id chunk-id) i))
                                                       ids))
                                                0)]
                                    (swap! !playground-chat-state assoc
                                           :reference-chunk-ids ids
                                           :reference-index (max 0 idx))))
        :scroll-to-message-id   (:scroll-to-message-id state)
        :on-scrolled            #(swap! !playground-chat-state assoc
                                        :scroll-to-message-id nil)})

    (when is-running
      (dom/div
       (dom/props {:style {:padding "0 1rem" :margin-bottom "0.5rem"}})
       (dom/div
        (dom/props {:style (merge assistant-message-style {:opacity "0.9"})})
        (observability/NextDetailedLive stream-view)
        (if (:waiting? stream-view)
          (let [thinking (:live-thinking stream-view)
                reasoning (:reasoning thinking)
                has-reasoning? (and (string? reasoning)
                                    (not (clojure.string/blank? reasoning)))]
            (dom/div
             (dom/props {:style {:color (if has-reasoning? "#1f2937" "#9ca3af")
                                 :font-style "italic"
                                 :line-height "1.5"
                                 :white-space "pre-wrap"
                                 :max-height "14rem"
                                 :overflow "auto"}})
             (dom/text (if has-reasoning? reasoning (t :playground/thinking)))))
          (let [html (e/server (base/render-markdown-to-html
                                (or (e/client (:content stream-view)) "")))]
            (dom/div
             (dom/props {:style {:line-height "1.6"}})
             (when html
               (base/set-markdown-html! dom/node html)))))))

      (when-let [error (:error stream-view)]
        (dom/div
         (dom/props {:style {:margin "0 1rem 0.5rem"
                             :padding "0.5rem"
                             :background "#fef2f2"
                             :border "1px solid #fecaca"
                             :border-radius "6px"
                             :font-size "0.8rem"
                             :color "#991b1b"}})
         (dom/text (t :playground/error-message error)))))

    (dom/div
     (dom/props {:style chat-input-style})
     (let [!local-query (atom "")
           local-query  (e/watch !local-query)]
       (dom/div
        (dom/props {:style {:display "flex"
                            :gap     "0.5rem"}})
        (dom/textarea
         (dom/props {:style       (merge textarea-style {:flex   "1"
                                                         :height "60px"
                                                         :resize "none"})
                     :aria-label (t :playground/message-placeholder)
                     :placeholder (t (if scope-complete?
                                       :playground/message-placeholder
                                       :playground/message-needs-scope))
                     :disabled (not scope-complete?)
                     :value       local-query})
         (dom/On "input" #(reset! !local-query (.. % -target -value)) nil)
         (dom/On "keydown"
                 #(when (and (= "Enter" (.-key %))
                             (not (.-shiftKey %))
                             (not (str/blank? local-query))
                            (not is-running)
                            scope-complete?)
                    (.preventDefault %)
                    (swap! !playground-chat-state assoc
                           :pending-send {:query         local-query
                                          :parent-msg-id last-msg-id
                                          :branch-index  0})
                    (reset! !local-query ""))
                 nil))
        (dom/button
         (dom/props {:style    {:padding       "0.5rem 1rem"
                                :background    (if (and (not (str/blank? local-query))
                                                        (not is-running)
                                                        scope-complete?)
                                                 "#3b82f6" "#9ca3af")
                                :color         "white"
                                :border        "none"
                                :border-radius "6px"
                                :font-weight   "500"
                                :cursor        (if (and (not (str/blank? local-query))
                                                        (not is-running)
                                                        scope-complete?)
                                                 "pointer" "not-allowed")}
                     :disabled (or (str/blank? local-query)
                                   is-running
                                   (not scope-complete?))})
         (dom/text (t (if is-running :playground/sending :playground/send)))
         (when (and (not (str/blank? local-query))
                    (not is-running)
                    scope-complete?)
           (let [[t err] (e/Token (dom/On "click" identity nil))]
             (when t
             (swap! !playground-chat-state assoc
                      :pending-send {:query         local-query
                                     :parent-msg-id last-msg-id
                                     :branch-index  0})
               (reset! !local-query "")
               (t))))))
       (dom/div
        (dom/props {:style {:font-size "0.72rem" :color "#6b7280"
                            :margin-top "0.35rem" :text-align "right"}})
        (dom/text (t :playground/input-shortcut)))))

    (EditMessageModal
     editing-msg-id
     editing-text
     (fn []
       (swap! !playground-chat-state assoc :editing-msg-id nil :editing-text ""))
     (fn [new-text]
       (when (and editing-msg-id (not (str/blank? new-text)))
         (let [edited-msg     (first (filter #(= editing-msg-id (:message/id %)) all-messages))
               parent-id      (get-in edited-msg [:message/parent-message :message/id])
               siblings       (chat-session/find-children all-messages parent-id)
               new-branch-idx (count siblings)]
           (swap! !playground-chat-state assoc
                  :pending-edit-submit {:parent-id  parent-id
                                        :query      new-text
                                        :branch-idx new-branch-idx})))))

    (when (and compare-mode (seq compare-execution-ids))
      (CompareRunsPanel
       compare-execution-ids
       (fn []
         (swap! !playground-chat-state assoc
                :compare-mode false
                :compare-execution-ids []))))))))

(e/defn PlaygroundChatEffects
  "Reactive side effects for playground chat state transitions."
  [state
   conversation-id
   active-branch-path
   effective-agent-id
   config
   is-running
   scope-complete?
   user-id
   effective-selected-tenant
   effective-selected-dataset-config-key
   selected-dataset-config]
  (let [pending-send (:pending-send state)
        pending-regenerate (:pending-regenerate state)
        pending-edit-submit (:pending-edit-submit state)
        pending-delete-convo-id (:pending-delete-convo-id state)
        pending-clear-all (:pending-clear-all state)
        pending-load-execution-for-convo (:pending-load-execution-for-convo state)
        pending-new-conversation (:pending-new-conversation state)]
    ;; Persist the effective, server-validated scope rather than leaving a
    ;; missing or revoked remembered value in browser storage. This runs at
    ;; most once per resolved change because the next render sees equality.
    (when (and scope-complete?
               (or (not= (:selected-agent-id state) effective-agent-id)
                   (not= (:selected-tenant state) effective-selected-tenant)
                   (not= (:selected-dataset-config-key state)
                         effective-selected-dataset-config-key)))
      (e/client
       (swap! !playground-chat-state assoc
              :selected-agent-id effective-agent-id
              :selected-tenant effective-selected-tenant
              :selected-dataset-config-key effective-selected-dataset-config-key)))

    (when (and pending-send (not is-running) scope-complete?)
      (let [result (e/server
                    (playground/execute-playground-chat-pipeline
                     {:conversation-id (e/client conversation-id)
                      :tenant          (e/client effective-selected-tenant)
                      :dataset-config-key (e/client effective-selected-dataset-config-key)
                      :agent-id        (e/client effective-agent-id)
                      :query           (e/client (:query pending-send))
                      :config          (e/client config)
                      :parent-msg-id   (e/client (:parent-msg-id pending-send))
                      :branch-index    (or (e/client (:branch-index pending-send)) 0)
                      :user-id         user-id}))]
        (swap! !playground-chat-state
               (fn [current]
                 (cond-> (assoc current
                                :conversation-id (:conversation-id result)
                                :execution-id (:execution-id result)
                                :scroll-to-message-id (:user-msg-id result)
                                :pending-send nil)
                   ;; The sidebar no longer watches active-conversation changes.
                   ;; Explicitly refresh only when send created a new row.
                   (nil? conversation-id)
                   (update :conversation-list-revision
                           (fn [n] (inc (or n 0)))))))))

    (when (and pending-regenerate (not is-running) scope-complete?)
      (let [result (e/server
                    (playground/execute-playground-chat-pipeline
                     {:conversation-id (e/client conversation-id)
                      :tenant          (e/client effective-selected-tenant)
                      :dataset-config-key (e/client effective-selected-dataset-config-key)
                      :agent-id        (e/client effective-agent-id)
                      :query           (e/client (:query pending-regenerate))
                      :config          (e/client config)
                      :parent-msg-id   (e/client (:parent-id pending-regenerate))
                      :branch-index    (e/client (:branch-idx pending-regenerate))
                      :user-id         user-id}))]
        (swap! !playground-chat-state assoc
               :execution-id (:execution-id result)
               :scroll-to-message-id (:user-msg-id result)
               :pending-regenerate nil)))

    (when (and pending-edit-submit (not is-running) scope-complete?)
      (let [result (e/server
                    (playground/execute-playground-chat-pipeline
                     {:conversation-id (e/client conversation-id)
                      :tenant          (e/client effective-selected-tenant)
                      :dataset-config-key (e/client effective-selected-dataset-config-key)
                      :agent-id        (e/client effective-agent-id)
                      :query           (e/client (:query pending-edit-submit))
                      :config          (e/client config)
                      :parent-msg-id   (e/client (:parent-id pending-edit-submit))
                      :branch-index    (e/client (:branch-idx pending-edit-submit))
                      :user-id         user-id}))]
        (swap! !playground-chat-state assoc
               :conversation-id (:conversation-id result)
               :execution-id (:execution-id result)
               :scroll-to-message-id (:user-msg-id result)
               :editing-msg-id nil
               :editing-text ""
               :pending-edit-submit nil)))

    (when-some [delete-id pending-delete-convo-id]
      (e/server
       (let [id-to-delete (e/client delete-id)
             uid user-id]
         (e/Offload #(db/delete-playground-conversation (db/get-conn) id-to-delete uid))))
      (e/client
       (swap! !playground-chat-state assoc
              :pending-delete-convo-id nil
              :conversation-id (when-not (= delete-id conversation-id) conversation-id)
              :active-branch-path (when-not (= delete-id conversation-id) active-branch-path)
              :conversation-list-revision
              (inc (or (:conversation-list-revision state) 0)))))

    (when pending-clear-all
      (e/server
       (let [uid user-id]
         (e/Offload #(db/clear-user-playground-conversations (db/get-conn) uid))))
      (e/client
       (swap! !playground-chat-state assoc
              :pending-clear-all nil
              :conversation-id nil
              :active-branch-path {}
              :conversation-list-revision
              (inc (or (:conversation-list-revision state) 0)))))

    (when-some [convo-id pending-load-execution-for-convo]
      (let [latest-exec-id (e/server
                            (playground/get-latest-execution-id-for-conversation
                             (e/client convo-id)))]
        (e/client
         (swap! !playground-chat-state assoc
                :pending-load-execution-for-convo nil
                :execution-id latest-exec-id))))

    (when pending-new-conversation
      (let [new-convo-id (e/server
                          (let [tenant (e/client effective-selected-tenant)
                                config-key (e/client effective-selected-dataset-config-key)
                                agent-id (e/client effective-agent-id)
                                uid user-id]
                            (e/Offload
                             #(let [result (db/create-playground-conversation
                                            (db/get-conn)
                                            agent-id
                                            {:user-id uid
                                             :tenant tenant
                                             :dataset-config-key config-key})]
                                (:conversation-id result)))))]
        (e/client
         (swap! !playground-chat-state assoc
                :pending-new-conversation nil
                :conversation-id new-convo-id
                :execution-id nil
                :config (initialize-chat-config config selected-dataset-config)
                :active-branch-path {}
                :conversation-list-revision
                (inc (or (:conversation-list-revision state) 0))))))
    nil))

(e/defn PlaygroundChatDebugNotice [debug-playground]
  (e/client
   (when (not= debug-playground "full")
     (dom/div
      (dom/props {:style {:padding "0.75rem 1rem"
                          :background "#eff6ff"
                          :border-bottom "1px solid #93c5fd"
                          :color "#1d4ed8"
                          :font-size "0.875rem"
                          :font-weight "600"}})
      (dom/text (str "Playground isolation mode: " debug-playground))))))

(e/defn PlaygroundChatScopeProbe [state]
  (e/client
   (let [selected-agent-id          (:selected-agent-id state)
         selected-tenant            (:selected-tenant state)
         selected-dataset-config-key (:selected-dataset-config-key state)
         config                     (:config state)

         user-id                    (e/server (auth/current-user-id e/http-request))
         all-tenants                (e/server (config-db/list-tenants @(db/get-conn)))
         accessible-tenants         (e/server
                                     (when user-id
                                       (perms/get-user-accessible-tenants
                                        @(db/get-conn) user-id all-tenants)))
         enabled-agents             (e/server
                                     (let [conn (config-db/get-conn)]
                                       (if conn
                                         (agents-db/list-enabled-agents @conn)
                                         [])))
         skill-graph-data           (e/server
                                     (load-skill-graph-options-data))
         effective-agent-id         (preferred-agent-id selected-agent-id config enabled-agents)
         selected-agent             (some #(when (= (:id %) effective-agent-id) %) enabled-agents)
         agent-dataset-scopes       (vec (:allowed-dataset-scopes selected-agent))
         visible-tenants            (if (seq agent-dataset-scopes)
                                     (filterv (set (allowed-option-values agent-dataset-scopes :tenant))
                                              (sort (or accessible-tenants [])))
                                     (sort (or accessible-tenants [])))
         effective-selected-tenant     (when (some #{selected-tenant} visible-tenants) selected-tenant)
         visible-dataset-scope-options (e/server
                                        (dataset-scope-options (e/client effective-selected-tenant)
                                                               (e/client agent-dataset-scopes)))
         visible-dataset-scope-keys (mapv :value (or visible-dataset-scope-options []))
         effective-skill-graph-data (restrict-skill-graph-data skill-graph-data selected-agent)
         effective-skill-graph     (effective-skill-graph-id config effective-skill-graph-data selected-agent)
         effective-config          (effective-chat-config config effective-skill-graph)]
     (dom/div
      (dom/props {:style {:padding "1rem"
                          :font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                          :font-size "0.875rem"
                          :line-height "1.6"}})
      (dom/div (dom/text (str "user-id: " user-id)))
      (dom/div (dom/text (str "selected-agent-id: " selected-agent-id)))
      (dom/div (dom/text (str "effective-agent-id: " effective-agent-id)))
      (dom/div (dom/text (str "selected-tenant: " selected-tenant)))
      (dom/div (dom/text (str "selected-dataset-config-key: " selected-dataset-config-key)))
      (dom/div (dom/text (str "agents: " (count enabled-agents))))
      (dom/div (dom/text (str "visible-tenants: " (count visible-tenants))))
      (dom/div (dom/text (str "visible-dataset-scope-keys: " (count visible-dataset-scope-keys))))
      (dom/div (dom/text (str "skill-graphs: " (count (:options effective-skill-graph-data)))))
      (dom/div (dom/text (str "effective-skill-graph: " (:skill-graph effective-config))))))))

(e/defn PlaygroundChatInspectProbe [state]
  (e/client
   (let [conversation-id           (:conversation-id state)
         selected-agent-id         (:selected-agent-id state)
         selected-tenant           (:selected-tenant state)
         selected-dataset-config-key (:selected-dataset-config-key state)
         config                    (:config state)
         execution-id              (:execution-id state)

         user-id                   (e/server (auth/current-user-id e/http-request))
         all-tenants               (e/server (config-db/list-tenants @(db/get-conn)))
         accessible-tenants        (e/server
                                     (when user-id
                                       (perms/get-user-accessible-tenants
                                        @(db/get-conn) user-id all-tenants)))
         enabled-agents            (e/server
                                     (let [conn (config-db/get-conn)]
                                       (if conn
                                         (agents-db/list-enabled-agents @conn)
                                         [])))
         skill-graph-data          (e/server
                                     (load-skill-graph-options-data))
         effective-agent-id        (preferred-agent-id selected-agent-id config enabled-agents)
         selected-agent            (some #(when (= (:id %) effective-agent-id) %) enabled-agents)
         agent-dataset-scopes      (vec (:allowed-dataset-scopes selected-agent))
         visible-tenants           (if (seq agent-dataset-scopes)
                                     (filterv (set (allowed-option-values agent-dataset-scopes :tenant))
                                              (sort (or accessible-tenants [])))
                                     (sort (or accessible-tenants [])))
         effective-selected-tenant (when (some #{selected-tenant} visible-tenants) selected-tenant)
         visible-dataset-scope-options (e/server
                                        (dataset-scope-options (e/client effective-selected-tenant)
                                                               (e/client agent-dataset-scopes)))
         visible-dataset-scope-keys (mapv :value (or visible-dataset-scope-options []))
         effective-selected-dataset-config-key
         (when (some #{selected-dataset-config-key} visible-dataset-scope-keys)
           selected-dataset-config-key)
         effective-skill-graph-data (restrict-skill-graph-data skill-graph-data selected-agent)
         effective-skill-graph     (effective-skill-graph-id config effective-skill-graph-data selected-agent)
         effective-config          (effective-chat-config config effective-skill-graph)
         reactive-db-probe         (e/server
                                     (safe-probe "reactive-db"
                                                 #(boolean (db/get-conn))))
         selected-dataset-probe    (e/server
                                     (safe-probe
                                      "selected-dataset"
                                      #(when (and effective-selected-tenant
                                                  effective-selected-dataset-config-key
                                                  effective-agent-id)
                                         (let [dataset-config (resolve-selected-dataset-config
                                                               effective-selected-tenant
                                                               effective-selected-dataset-config-key
                                                               effective-agent-id)]
                                           (select-keys dataset-config
                                                        [:id :name :docs-collection
                                                         :chunks-collection :phrases-collection])))))
         conversations-probe       (e/server
                                     (safe-probe
                                      "conversations"
                                      #(if user-id
                                         (count (db/playground-conversations-by-user
                                                 @(db/get-conn)
                                                 user-id))
                                         0)))
         messages-probe            (e/server
                                     (safe-probe
                                      "messages"
                                      #(if conversation-id
                                         (count (db/fetch-conversation-tree
                                                 @(db/get-conn)
                                                 conversation-id))
                                         0)))
         execution-probe           (e/server
                                     (safe-probe
                                      "execution"
                                      #(if execution-id
                                         (boolean (playground/get-execution execution-id))
                                         false)))
         probes [reactive-db-probe
                 selected-dataset-probe
                 conversations-probe
                 messages-probe
                 execution-probe]]
     (dom/div
      (dom/props {:style {:padding "1rem"
                          :font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                          :font-size "0.875rem"
                          :line-height "1.6"}})
      (dom/div (dom/text (str "conversation-id: " conversation-id)))
      (dom/div (dom/text (str "effective-agent-id: " effective-agent-id)))
      (dom/div (dom/text (str "effective-tenant: " effective-selected-tenant)))
      (dom/div (dom/text (str "effective-dataset-config-key: " effective-selected-dataset-config-key)))
      (dom/div (dom/text (str "effective-skill-graph: " (:skill-graph effective-config))))
      (dom/div
       (dom/props {:style {:margin-top "0.75rem"}})
       (e/for-by :label [probe probes]
         (dom/div
          (dom/text
           (if (:ok probe)
             (str (:label probe) ": ok " (pr-str (:value probe)))
             (str (:label probe) ": error "
                  (get-in probe [:error :class]) " | "
                  (get-in probe [:error :message])))))))))))

(defn ^:no-doc derive-chat-tenant-state
  "Pure-CLJ derivation of tenant/agent/dataset state from raw server values."
  [state user-id accessible-tenants enabled-agents skill-graph-data]
  (let [{:keys [selected-agent-id selected-tenant selected-dataset-config-key
                config]} state
        agent-names (into {} (map (juxt :id :name) enabled-agents))
        effective-agent-id (preferred-agent-id selected-agent-id config enabled-agents)
        selected-agent (some #(when (= (:id %) effective-agent-id) %) enabled-agents)
        agent-dataset-scopes (vec (:allowed-dataset-scopes selected-agent))
        unrestricted-agent? (some #(empty? (:allowed-dataset-scopes %)) enabled-agents)
        available-agent-dataset-scopes
        (if unrestricted-agent?
          []
          (vec (mapcat :allowed-dataset-scopes enabled-agents)))
        visible-tenants (if (seq available-agent-dataset-scopes)
                          (filterv (set (allowed-option-values available-agent-dataset-scopes :tenant))
                                   (sort (or accessible-tenants [])))
                          (sort (or accessible-tenants [])))
        ;; Restore the last valid tenant; on a first visit (or after access
        ;; changes), select the first server-authorized option so the user does
        ;; not have to assemble an obvious scope by hand.
        effective-selected-tenant (preferred-option selected-tenant visible-tenants)
        effective-skill-graph-data (restrict-skill-graph-data skill-graph-data selected-agent)
        effective-skill-graph (effective-skill-graph-id config effective-skill-graph-data selected-agent)
        effective-config (effective-chat-config config effective-skill-graph)]
    {:user-id user-id
     :agent-names agent-names
     :effective-agent-id effective-agent-id
     :agent-dataset-scopes agent-dataset-scopes
     :available-agent-dataset-scopes available-agent-dataset-scopes
     :visible-tenants visible-tenants
     :effective-selected-tenant effective-selected-tenant
     :effective-skill-graph-data effective-skill-graph-data
     :effective-config effective-config
     :selected-dataset-config-key selected-dataset-config-key}))

(defn ^:no-doc derive-chat-scope-state
  "Pure-CLJ derivation of resolved scope flags and config."
  [tenant-state selected-dataset-config visible-dataset-scope-options]
  (let [{:keys [effective-agent-id effective-selected-tenant agent-dataset-scopes
                selected-dataset-config-key]} tenant-state
        visible-dataset-scope-keys (mapv :value (or visible-dataset-scope-options []))
        effective-selected-dataset-config-key
        (preferred-option selected-dataset-config-key visible-dataset-scope-keys)
        valid-dataset-selection? (and (not (str/blank? effective-selected-tenant))
                                      (not (str/blank? effective-selected-dataset-config-key))
                                      (dataset-scope-allowed? agent-dataset-scopes
                                                              effective-selected-tenant
                                                              effective-selected-dataset-config-key)
                                      (some? selected-dataset-config))
        has-agent? (not (str/blank? effective-agent-id))
        has-tenant? (not (str/blank? effective-selected-tenant))
        has-config-key? (not (str/blank? effective-selected-dataset-config-key))
        resolution-level (cond
                           (and has-agent? has-tenant? has-config-key?) 1
                           (and has-agent? has-tenant?) 2
                           (and has-agent? has-config-key?) 3
                           has-agent? 4
                           (and has-tenant? has-config-key?) 5
                           has-tenant? 6
                           has-config-key? 7
                           :else 8)
        scope-complete? (and (not (str/blank? effective-agent-id)) valid-dataset-selection?)]
    {:visible-dataset-scope-keys visible-dataset-scope-keys
     :effective-selected-dataset-config-key effective-selected-dataset-config-key
     :resolution-level resolution-level
     :scope-complete? scope-complete?}))

(defn ^:no-doc derive-chat-message-state
  "Pure-CLJ derivation of message/execution view-model from raw data."
  [active-branch-path all-messages execution]
  (let [visible-messages (chat-session/get-visible-messages (or all-messages []) active-branch-path)
        stream-view (chat-session/execution-stream-view execution)]
    {:visible-messages visible-messages
     :stream-view stream-view
     :is-running (:running? stream-view)
     :last-msg-id (:message/id (last visible-messages))}))

(e/defn PlaygroundChatFullLayout
  "Conversation pane content. The stable sidebar is rendered by a sibling
   reactor so slow message refreshes cannot revoke it."
  [{:keys [state derived conversation-id enabled-agents
           tenant-names selected-dataset-config dataset-stats]}]
  (e/client
   (dom/div
    ;; Preserve the outer flex relationship while giving this independently
    ;; suspending subtree a DOM boundary of its own.
    (dom/props {:style {:display "contents"}})

    (PlaygroundConversationPane
     state
     conversation-id
     (:effective-agent-id derived)
     enabled-agents
     (:effective-selected-tenant derived)
     (:visible-tenants derived)
     tenant-names
     (:effective-selected-dataset-config-key derived)
     (:visible-dataset-scope-keys derived)
     (:resolution-level derived)
     (:scope-complete? derived)
     selected-dataset-config
     dataset-stats
     (:visible-messages derived)
     (:all-messages derived)
     (:active-branch-path state)
     (:stream-view derived)
     (:is-running derived)
     (:effective-config derived)
     (:last-msg-id derived)
     (:editing-msg-id state)
     (:editing-text state)
     (:compare-mode state)
     (:compare-execution-ids state))

    (let [reference (selected-reference state)
          chunk-id (:chunk-id reference)
          index (:index reference)
          total (:total reference)]
      (when (and selected-dataset-config chunk-id)
        (DiagnosticsChunkPanel
         chunk-id
         (:docs-collection selected-dataset-config)
         (:chunks-collection selected-dataset-config)
         ;; Keep the ids cached and close by changing only the discriminant.
         ;; This avoids a transient index-plus-empty-vector combination.
         #(swap! !playground-chat-state assoc :reference-index nil)
         index
         total
         #(swap! !playground-chat-state update :reference-index
                 (fn [current] (max 0 (dec (or current 0)))))
         #(swap! !playground-chat-state update :reference-index
                 (fn [current] (min (dec total)
                                    (inc (or current 0)))))))))))

(e/defn PlaygroundChatFull
  "Multi-message chat playground with persistence and diagnostics."
  [state render-mode run-effects?]
  (e/client
   (let [conversation-id (:conversation-id state)
         user-id (e/server (auth/current-user-id e/http-request))
         all-tenants (e/server (config-db/list-tenants @(db/get-conn)))
         tenant-names (e/server (config-db/get-all-tenant-names @(db/get-conn)))
         accessible-tenants (e/server
                              (when user-id
                                (perms/get-user-accessible-tenants
                                 @(db/get-conn) user-id all-tenants)))
         enabled-agents (e/server
                          (let [conn (config-db/get-conn)]
                            (if conn (agents-db/list-enabled-agents @conn) [])))
         skill-graph-data (e/server (load-skill-graph-options-data))
         tenant-state (derive-chat-tenant-state state user-id accessible-tenants
                                                enabled-agents skill-graph-data)
         visible-dataset-scope-options (e/server
                                         (dataset-scope-options
                                          (e/client (:effective-selected-tenant tenant-state))
                                          (e/client (:available-agent-dataset-scopes tenant-state))))
         preferred-dataset-config-key (preferred-option
                                       (:selected-dataset-config-key tenant-state)
                                       (mapv :value visible-dataset-scope-options))
         scope-enabled-agents (eligible-agents-for-scope
                               enabled-agents
                               (:effective-selected-tenant tenant-state)
                               preferred-dataset-config-key)
         effective-agent-id (preferred-agent-id
                             (:selected-agent-id state)
                             (:config state)
                             scope-enabled-agents)
         selected-agent (some #(when (= (:id %) effective-agent-id) %)
                              scope-enabled-agents)
         effective-skill-graph-data (restrict-skill-graph-data
                                     skill-graph-data selected-agent)
         effective-skill-graph (effective-skill-graph-id
                                (:config state)
                                effective-skill-graph-data
                                selected-agent)
         final-tenant-state (assoc tenant-state
                                   :effective-agent-id effective-agent-id
                                   :agent-dataset-scopes
                                   (vec (:allowed-dataset-scopes selected-agent))
                                   :effective-skill-graph-data effective-skill-graph-data
                                   :effective-config
                                   (effective-chat-config (:config state)
                                                          effective-skill-graph))
         selected-dataset-config (e/server
                                   (resolve-selected-dataset-config
                                    (e/client (:effective-selected-tenant final-tenant-state))
                                    (e/client preferred-dataset-config-key)
                                    (e/client (:effective-agent-id final-tenant-state))))
         dataset-stats (when selected-dataset-config
                         (e/server
                          (let [dataset-config (e/client selected-dataset-config)
                                tenant (e/client (:effective-selected-tenant final-tenant-state))
                                dataset-config-key (e/client preferred-dataset-config-key)]
                            (e/Offload
                             #(playground/fetch-dataset-stats
                               dataset-config
                               {:tenant tenant
                                :dataset-config-key dataset-config-key})))))
         scope-state (derive-chat-scope-state
                      (assoc final-tenant-state
                             :selected-dataset-config-key preferred-dataset-config-key)
                      selected-dataset-config
                                              visible-dataset-scope-options)
         effective-config (cond-> (:effective-config final-tenant-state)
                            selected-dataset-config
                            (assoc :default-model (:synthesis-model selected-dataset-config)
                                   :available-models (:available-models selected-dataset-config)))
         ;; NOTE: do NOT use `(e/watch (db/get-conn))` here. A Datahike
         ;; connection is an IRef but not a `clojure.lang.Atom`, and
         ;; Electric's e/watch yields a Keyword sentinel for that subtype
         ;; instead of the dereferenced DB value. The Keyword then
         ;; reaches `d/q` and Datahike throws
         ;; "Don't know how to create ISeq from: clojure.lang.Keyword".
         ;; Confirmed via the diagnostic in `digdir.data.db/fetch-conversation-tree`
         ;; — see commit history around 2026-05-18.
         ;; Dereferencing the connection fresh inside each `e/Offload`
         ;; thunk avoids the bad watch and runs on the offload thread.
         ;; Re-runs naturally when the conversation/execution refresh inputs
         ;; below change.
         all-messages (when conversation-id
                        (e/server
                         (let [convo-id (e/client conversation-id)
                               ;; Reactive refetch trigger: track the
                               ;; current execution's :status and
                               ;; :assistant-msg-id. Without this hook,
                               ;; e/Offload only fires once when
                               ;; conversation-id is first set, and the
                               ;; assistant message transacted later by
                               ;; the background-worker doesn't appear
                               ;; until a full page reload (which
                               ;; re-initializes the reactive fetch).
                               ;; By depending on the execution map's
                               ;; :status and :assistant-msg-id, this
                               ;; let-binding re-evaluates on those
                               ;; transitions, which forces e/Offload
                               ;; to re-run and pick up the new tree
                               ;; from Datahike. 2026-05-19.
                               _refresh-trigger
                               (when-let [eid (e/client (:execution-id state))]
                                 (let [exec (e/watch (l/derive (l/key eid)
                                                               playground/!playground-executions))]
                                   ;; Three signals are watched; any
                                   ;; one of them forces an Offload
                                   ;; refetch. :status catches the
                                   ;; running → complete flip,
                                   ;; :assistant-msg-id catches the
                                   ;; queue-return moment, and
                                   ;; :assistant-msg-committed-at
                                   ;; catches the background-worker
                                   ;; commit moment (which is the one
                                   ;; that actually puts the row in
                                   ;; Datahike — set via callback in
                                   ;; playground.core's
                                   ;; queue-playground-assistant-msg!
                                   ;; call site).
                                   [(:status exec)
                                    (:assistant-msg-id exec)
                                    (:assistant-msg-committed-at exec)]))]
                           (e/Offload
                            #(let [tree (db/fetch-conversation-tree @(db/get-conn) convo-id)]
                               ;; Diagnostic for "user/assistant bubbles
                               ;; disappear after graph completion"
                               ;; (2026-05-19). Each line shows when a
                               ;; refetch fired and what it returned.
                               (timbre/info :playground/fetch-conversation-tree
                                            {:convo-id convo-id
                                             :triggered-by _refresh-trigger
                                             :count (count tree)
                                             :roles (frequencies (map :message/role tree))
                                             :text-lens (mapv (fn [m] (count (or (:message/text m) "")))
                                                              tree)})
                               tree)))))
         execution (when-let [eid (:execution-id state)]
                     (e/server
                      (e/watch (l/derive (l/key (e/client eid))
                                         playground/!playground-executions))))
         msg-state (derive-chat-message-state (:active-branch-path state) all-messages execution)
         derived (merge final-tenant-state scope-state msg-state
                        {:all-messages all-messages
                         :effective-config effective-config})]

     (when run-effects?
       (PlaygroundChatEffects
        state
        conversation-id
        (:active-branch-path state)
        (:effective-agent-id derived)
        (:effective-config derived)
        (:is-running derived)
        (:scope-complete? derived)
        user-id
        (:effective-selected-tenant derived)
        (:effective-selected-dataset-config-key derived)
        selected-dataset-config))

     (if (= render-mode :probe)
       (dom/div
        (dom/props {:style {:padding "1rem"
                            :font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                            :font-size "0.875rem"
                            :line-height "1.6"}})
        (dom/div (dom/text (str "conversation-id: " conversation-id)))
        (dom/div (dom/text (str "effective-agent-id: " (:effective-agent-id derived))))
        (dom/div (dom/text (str "scope-complete?: " (:scope-complete? derived))))
        (dom/div (dom/text (str "all-messages: " (count (or all-messages [])))))
        (dom/div (dom/text (str "visible-messages: " (count (:visible-messages derived)))))
        (dom/div (dom/text (str "execution-id: " (:execution-id state))))
        (dom/div (dom/text (str "is-running: " (:is-running derived)))))
       (PlaygroundChatFullLayout
        {:state state
         :derived derived
         :conversation-id conversation-id
         :enabled-agents scope-enabled-agents
         :tenant-names tenant-names
         :selected-dataset-config selected-dataset-config
         :dataset-stats dataset-stats})))))

(e/defn PlaygroundChatWorkspace
  "Keep the sidebar mounted while the independently reactive conversation
   pane loads messages, dataset statistics, and execution updates."
  [state render-mode run-effects?]
  (e/client
   (let [{:keys [conversation-id page-size search-query revision]}
         (conversation-sidebar-state state)]
     (if (= render-mode :probe)
       (PlaygroundChatFull state render-mode run-effects?)
       (dom/div
        (dom/props {:style {:display "flex"
                            :height "calc(100vh - 140px)"
                            :background "#fff"}})
        (when (:show-sidebar state)
          (PlaygroundConversationSidebarRegion
           conversation-id page-size search-query revision))
        (PlaygroundChatFull state render-mode run-effects?))))))

(e/defn PlaygroundChat
  [ts-settings]
  (e/client
   (let [state            (e/watch !playground-chat-state)
         debug-playground (e/server
                           (observability/normalize-debug-playground-mode
                            (get-in e/http-request [:query-params "debug-playground"])))]
     ts-settings
     (PlaygroundChatDebugNotice debug-playground)
     (case debug-playground
       "bare"
       (dom/div
        (dom/props {:style {:padding "1rem"}})
        (dom/text "Playground bare mode"))

       "scope"
       (PlaygroundChatScopeProbe state)

       "inspect"
       (PlaygroundChatInspectProbe state)

       "data"
       (PlaygroundChatWorkspace state :probe false)

       "no-effects"
       (PlaygroundChatWorkspace state :full false)

       (PlaygroundChatWorkspace state :full true)))))
