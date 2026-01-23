(ns digdir.ui.components
  "Reusable UI components shared across the application."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            [com.itonomi.komponentkassen.shell :as ks]
            [lambdaisland.deep-diff2 :as ddiff]
            [digdir.util.ui :refer [pprint-str]]
            [digdir.auth.ui :refer [ProfileMenu]]
            [digdir.ui.routing :as routing]
            #?(:clj [digdir.rag.ui.status :as status])
            #?(:clj [digdir.docs.loader :as document-loading])))

;; =========Frontend state=========
#?(:cljs
   (defonce !state (atom {:tabs {:active-tab 0}})))

(defn print-diff [old new]
  (-> (ddiff/diff old new)
      ddiff/minimize
      ddiff/pretty-print ;; Printing directly with
                       ;; ddiff/pretty-print does not look
                       ;; right
      with-out-str
      print))

#?(:cljs
   (add-watch !state :print-diff
              (fn [_key _atom old new]
                (print-diff old new))))

;; =========Tab Components=========

(e/defn Tabs [& tab-pairs]
  (let [tab-pairs (partition 2 tab-pairs)
        !active-tab (atom 0)
        active-tab (e/watch !active-tab)]
    (ks/Tabs {}
             (e/fn []
               (ks/TabsList {}
                            (e/fn []
                              (e/for [[idx [Tab Panel]] (e/diff-by identity (map-indexed vector tab-pairs))]
                                (ks/TabsTab {:aria-selected (= idx active-tab)}
                                            (e/fn []
                                              (dom/On "click" #(reset! !active-tab idx) nil)
                                              (Tab))))))
               (ks/TabsPanel {}
                             (e/fn []
                               (let [[Tab Panel] (nth tab-pairs active-tab)]
                                 (Panel))))))))

(defmacro tabs [& tab-pairs]
  `(Tabs ~@(map (fn [t] `(e/fn [] ~t)) tab-pairs)))

;; =========Routed Tab Components=========

(e/defn RoutedTabs
  "Tab component that syncs with URL routing.

   Args:
     route-group - Keyword identifying the route group (:main, :config, :import)
     level - Nesting level (0 for main tabs, 1 for sub-tabs, etc.)
     tab-pairs - Pairs of [Tab-content Panel-content]"
  [route-group level & tab-pairs]
  (e/client
   (let [tab-pairs (partition 2 tab-pairs)
         [active-tab set-active-tab!] (routing/UseRoutedTab route-group level)]
     (ks/Tabs {}
              (e/fn []
                (ks/TabsList {}
                             (e/fn []
                               (e/for [[idx [Tab Panel]] (e/diff-by identity (map-indexed vector tab-pairs))]
                                 (ks/TabsTab {:aria-selected (= idx active-tab)}
                                             (e/fn []
                                               (let [[token _err] (e/Token (dom/On "click" identity nil))]
                                                 (when token
                                                   (set-active-tab! idx)
                                                   (token)))
                                               (Tab))))))
                (ks/TabsPanel {}
                              (e/fn []
                                (let [[Tab Panel] (nth tab-pairs active-tab)]
                                  (Panel)))))))))

(defmacro routed-tabs
  "Macro for routed tabs.
   Usage: (routed-tabs :main 0
            (dom/text \"Chat\") (ChatPanel)
            (dom/text \"Config\") (ConfigPanel))"
  [route-group level & tab-pairs]
  `(RoutedTabs ~route-group ~level
               ~@(map (fn [t] `(e/fn [] ~t)) tab-pairs)))

;; =========Status & Display Components=========

(e/defn DocumentLoadingJobStatus [status]
  (ks/Card {:style {:margin-top "0.5rem"}}
           (e/fn []
             (if status
               (dom/pre (dom/text (pprint-str status)))
               (ks/Paragraph {}
                             (e/fn [] (dom/text "No job is currently running...")))))))

(e/defn SignalWindow
  "document-loading/!signal-window contains some things that Electric doesn't like
   sending over the wire. Here we preprocess it so traveling otw is fine."
  []
  (e/server (map #(-> %
                      (update :msg_ force)
                       ;; TODO: teach electric to transfer time and error values otw
                      (update :inst str)
                      (update :end-inst str)
                      (update :error str))
                 (take 37 (e/watch document-loading/!signal-window)))))

(e/defn AllSignals
  "document-loading/!signal-window contains some things that Electric doesn't like
   sending over the wire. Here we preprocess it so traveling otw is fine."
  []
  (e/server (sequence
             (map #(-> %
                       (update :msg_ force)
                       ;; TODO: teach electric to transfer time and error values otw
                       (update :inst str)
                       (update :end-inst str)
                       (update :error str)))
             (e/watch document-loading/!signal-window))))

;; =========Data Display Components=========

(e/defn KVTable [rows]
  (e/client
   (e/for [[k v] (e/diff-by identity (sort-by first rows))]
      ;; TODO: ks table
     (dom/table
      (dom/tbody
       (dom/tr
        (dom/th (dom/props {:style {:width "700px"}})
                (dom/text (if (nil? k)
                            "nil"
                            k)))
        (dom/td (dom/text v))))))))

;; =========Admin Header Bar=========

(e/defn StatusBar [ts-settings user-email]
  (e/client
   (dom/div
    (dom/props {:style {:display "flex"
                        :align-items "center"
                        :gap "1rem"
                        :padding "0.75rem 1rem"
                        :background "#f8fafc"
                        :border "1px solid #e2e8f0"
                        :border-radius "6px"
                        :margin-bottom "1rem"
                        :font-size "0.875rem"}})
    ;; Typesense status (inline version)
    (let [api-host (e/server (:uri ts-settings))
          health-data (e/server (#?(:clj status/vibed-get-typesense-health
                                    :cljs (fn [_] {:error "Server only"})) ts-settings))]
      (dom/div
       (dom/props {:style {:display "flex"
                           :align-items "center"
                           :gap "0.5rem"}})
       (dom/span
        (dom/props {:style {:font-weight "600"
                            :color "#475569"}})
        (dom/text "Typesense"))
       (dom/span
        (dom/props {:style {:font-family "monospace"
                            :background "#e2e8f0"
                            :padding "0.25rem 0.5rem"
                            :border-radius "4px"
                            :font-size "0.8rem"}})
        (dom/text (or api-host "Not set")))
       (if (:error health-data)
         (dom/span
          (dom/props {:style {:color "#dc2626"}})
          (dom/text "Error"))
         (dom/span
          (dom/props {:style {:display "flex"
                              :align-items "center"
                              :gap "0.25rem"}})
          (dom/span
           (dom/props {:style {:width "8px"
                               :height "8px"
                               :border-radius "50%"
                               :background (if (:ok health-data) "#22c55e" "#ef4444")}})
           (dom/text ""))
          (dom/span
           (dom/props {:style {:color (if (:ok health-data) "#16a34a" "#dc2626")
                               :font-weight "500"}})
           (dom/text (if (:ok health-data) "Healthy" "Unhealthy")))))))
    ;; Profile menu (pushed to right with margin-left: auto)
    (ProfileMenu user-email))))
