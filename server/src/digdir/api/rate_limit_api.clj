(ns digdir.api.rate-limit-api
  "Per-API-key sliding-window rate limiter for application endpoints.

   `digdir.api.rate-limit` is auth-endpoint-only (IP-based, brute-force
   protection). This is the application-level counterpart, keyed on the
   authenticated `:api-key/id`. Applied to /api/mcp (and any future
   endpoint that wants per-key throttling)."
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [ring.util.response :as res]))

(def ^:private default-window-ms (* 60 1000))
(def ^:private default-max-requests 120)

(defonce rate-limit-store (atom {}))

(defn- prune-window
  [timestamps cutoff]
  (vec (drop-while #(< % cutoff) timestamps)))

(defn- record-attempt!
  "Append `now` to the API key's request log, dropping entries older than
   `cutoff`. Returns the post-update request count within the window."
  [api-key-id now window-ms]
  (let [cutoff (- now window-ms)
        next-store (swap! rate-limit-store
                          (fn [store]
                            (let [timestamps (get store api-key-id [])
                                  pruned (prune-window timestamps cutoff)]
                              (assoc store api-key-id (conj pruned now)))))]
    (count (get next-store api-key-id))))

(defn wrap-api-rate-limit
  "Per-API-key rate limiter middleware.

   Reads `:api-key/id` from the request (set by `wrap-api-key-auth`) and
   counts requests within a sliding window. Returns 429 when the cap is
   exceeded. Options:
     :window-ms     — defaults to 60_000
     :max-requests  — defaults to 120 requests per window"
  ([handler] (wrap-api-rate-limit handler {}))
  ([handler {:keys [window-ms max-requests]
             :or {window-ms default-window-ms
                  max-requests default-max-requests}}]
   (fn [request]
     (let [api-key-id (:api-key/id request)]
       (if (nil? api-key-id)
         (handler request)
         (let [now (System/currentTimeMillis)
               count (record-attempt! api-key-id now window-ms)]
           (if (> count max-requests)
             (do
               (log/warn (str "API rate limit exceeded for key " api-key-id
                              " (" count "/" max-requests " in " window-ms "ms)"))
               (-> (res/response (json/generate-string
                                  {:error {:code "rate_limited"
                                           :message "Too many requests"
                                           :limit max-requests
                                           :window_ms window-ms}}))
                   (res/status 429)
                   (res/content-type "application/json")))
             (handler request))))))))

(defn reset-store!
  "Clear the rate-limit store. For tests."
  []
  (reset! rate-limit-store {}))
