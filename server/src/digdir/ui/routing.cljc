(ns digdir.ui.routing
  "URL-based routing for Electric/Hyperfiddle applications.

   Provides reactive URL state synchronization for tabs and filters.
   Uses browser History API for navigation without page reloads."
  (:require [hyperfiddle.electric3 :as e]
            [clojure.string :as str]))

;; =========Route State=========

#?(:cljs
   (defonce !route-state
     (atom {:path []
            :initialized false})))

;; =========Path Parsing=========

(defn parse-path
  "Parse URL pathname into path segments.
   '/config/audit' -> [\"config\" \"audit\"]
   '/' -> []"
  [pathname]
  (let [clean-path (-> pathname
                       (str/replace #"^/" "")
                       (str/replace #"/$" ""))]
    (if (str/blank? clean-path)
      []
      (vec (str/split clean-path #"/")))))

(defn build-path
  "Build URL pathname from path segments.
   [\"config\" \"audit\"] -> \"/config/audit\"
   [] -> \"/\""
  [segments]
  (if (empty? segments)
    "/"
    (str "/" (str/join "/" segments))))

;; =========Route Configuration=========

(def route-config
  "Route definitions map tab identifiers to URL segments."
  {:main {:tabs [{:id :chat :segment "chat"}
                 {:id :config :segment "config"}
                 {:id :import :segment "import"}
                 {:id :access-control :segment "access-control"}]
          :default :chat}

   :config {:tabs [{:id :config :segment "config"}
                   {:id :audit :segment "audit"}
                   {:id :permissions :segment "permissions"}
                   {:id :api-keys :segment "api-keys"}
                   {:id :pipelines :segment "pipelines"}
                   {:id :skills :segment "skills"}]
            :default :config}

   :import {:tabs [{:id :kudos :segment "kudos"}
                   {:id :folder :segment "folder"}
                   {:id :optimizely :segment "optimizely"}
                   {:id :website :segment "website"}]
            :default :kudos}

   :config-category {:type :filter
                     :default "all"}

   :config-view-mode {:type :filter
                      :values [:simple :inheritance]
                      :default "simple"}})

;; =========Browser History API=========

#?(:cljs
   (defn push-state!
     "Update browser URL without reload using pushState."
     [path-segments]
     (let [new-path (build-path path-segments)]
       (.pushState js/window.history nil "" new-path))))

#?(:cljs
   (defn replace-state!
     "Replace current URL without adding history entry."
     [path-segments]
     (let [new-path (build-path path-segments)]
       (.replaceState js/window.history nil "" new-path))))

#?(:cljs
   (defn get-current-path
     "Get current URL path as segments."
     []
     (parse-path (.-pathname js/window.location))))

;; =========Path Segment Resolution=========

(defn get-segment-at-level
  "Get the path segment at a specific nesting level.
   Level 0 = main tabs, Level 1 = sub-tabs, etc."
  [path level]
  (get path level))

(defn resolve-tab-from-segment
  "Resolve a segment to tab index for a given route group.
   Returns the index of the matching tab, or 0 if not found."
  [route-group segment]
  (let [config (get route-config route-group)
        tabs (:tabs config)
        default-id (:default config)]
    (if segment
      ;; Try to find matching segment
      (let [match (->> tabs
                       (map-indexed vector)
                       (filter #(= segment (:segment (second %))))
                       first)]
        (if match
          (first match)
          ;; Segment not found, return default
          (let [default-match (->> tabs
                                   (map-indexed vector)
                                   (filter #(= default-id (:id (second %))))
                                   first)]
            (if default-match (first default-match) 0))))
      ;; No segment provided, return default
      (let [default-match (->> tabs
                               (map-indexed vector)
                               (filter #(= default-id (:id (second %))))
                               first)]
        (if default-match (first default-match) 0)))))

(defn resolve-segment-from-tab
  "Get URL segment for a tab index in a route group."
  [route-group tab-index]
  (let [tabs (:tabs (get route-config route-group))]
    (when (and tabs (< tab-index (count tabs)))
      (:segment (nth tabs tab-index)))))

(defn resolve-filter-value
  "Resolve a segment to filter value, or return default."
  [filter-key segment]
  (let [config (get route-config filter-key)
        default-val (:default config)]
    (or segment default-val)))

;; =========Path Updates=========

(defn update-path-at-level
  "Update path at a specific level, truncating any deeper segments.
   This handles the case where changing a parent tab should clear child selections."
  [current-path level new-segment]
  (let [base-path (vec (take level current-path))]
    (conj base-path new-segment)))

#?(:cljs
   (defn navigate-to!
     "Navigate to a new path, updating browser history.
      Optionally replaces current history entry instead of pushing."
     ([path-segments] (navigate-to! path-segments false))
     ([path-segments replace?]
      (if replace?
        (replace-state! path-segments)
        (push-state! path-segments))
      (swap! !route-state assoc :path path-segments))))

;; =========Popstate Handling=========

#?(:cljs
   (defonce ^:private popstate-listener-added (atom false)))

#?(:cljs
   (defn setup-popstate-listener!
     "Setup listener for browser back/forward navigation.
      Should be called once during app initialization."
     []
     (when-not @popstate-listener-added
       (reset! popstate-listener-added true)
       (.addEventListener
        js/window
        "popstate"
        (fn [_event]
          (let [new-path (get-current-path)]
            (swap! !route-state assoc :path new-path)))))))

;; =========Electric Hooks=========

(e/defn UseRoute
  "Reactive hook for route state. Returns [route-state navigate-fn].

   Initializes routing on first render and sets up popstate listener."
  []
  (e/client
   (let [state (e/watch !route-state)]
     ;; Initialize on first render
     (when-not (:initialized state)
       (let [initial-path (get-current-path)]
         (swap! !route-state assoc :path initial-path :initialized true)
         (setup-popstate-listener!)))
     [state navigate-to!])))

(e/defn UseRoutedTab
  "Hook for a tab component that syncs with URL routing.

   Args:
     route-group - Keyword identifying the route group (:main, :config, :import)
     level - Nesting level (0 for main tabs, 1 for sub-tabs, etc.)

   Returns:
     [active-tab-index set-active-tab!]"
  [route-group level]
  (e/client
   (let [[route-state navigate!] (UseRoute)
         path (:path route-state)
         current-segment (get-segment-at-level path level)
         active-tab (resolve-tab-from-segment route-group current-segment)]
     [active-tab
      (fn [new-tab-index]
        (let [new-segment (resolve-segment-from-tab route-group new-tab-index)
              new-path (update-path-at-level path level new-segment)]
          (navigate! new-path)))])))

(e/defn UseRoutedFilter
  "Hook for filter components that sync with URL routing.
   Filters are additional path segments after the main tab hierarchy.

   Args:
     filter-id - Keyword identifying the filter (:config-category, :config-view-mode)
     level - Nesting level where this filter appears in the path

   Returns:
     [current-value set-value!]"
  [filter-id level]
  (e/client
   (let [[route-state navigate!] (UseRoute)
         path (:path route-state)
         current-segment (get-segment-at-level path level)
         current-value (resolve-filter-value filter-id current-segment)]
     [current-value
      (fn [new-value]
        (let [new-segment (if (keyword? new-value) (name new-value) (str new-value))
              new-path (update-path-at-level path level new-segment)]
          (navigate! new-path)))])))
