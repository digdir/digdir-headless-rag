(ns digdir.util.logging
  "Utilities for safe and efficient logging of complex data structures.

   Includes Telemere wrappers for automatic truncation of RAG pipeline data."
  (:require [clojure.string :as str]
            [taoensso.telemere :as t]))

;; ============================================================================
;; Configuration defaults
;; ============================================================================

(def ^:private default-opts
  {:max-string-len 100
   :max-coll-items 10
   :max-depth 10
   :ellipsis "..."})

;; ============================================================================
;; Core truncation function
;; ============================================================================

(declare truncate-value)

(defn truncate-for-logging
  "Recursively truncate data structures for safe and readable logging.

   Options map can include:
   - :max-string-len  Maximum characters for strings (default 100)
   - :max-coll-items  Maximum items in collections (default 10)
   - :max-depth       Maximum recursion depth (default 5)
   - :ellipsis        String to append when truncating (default '...')

   Examples:
   (truncate-for-logging \"very long string\")
   (truncate-for-logging {:key \"long value\"} {:max-string-len 50})
   (truncate-for-logging [1 2 3 4 5 6 7 8 9 10 11] {:max-coll-items 5})"
  ([value]
   (truncate-for-logging value {}))
  ([value opts]
   (let [opts (merge default-opts opts)]
     (truncate-value value opts 0))))

(defn- truncate-value
  "Internal recursive function for truncating values"
  [value {:keys [max-string-len max-coll-items max-depth ellipsis] :as opts} current-depth]
  (cond
    ;; Depth limit reached
    (>= current-depth max-depth)
    (str "<max-depth:" max-depth ">")

    ;; nil stays nil
    (nil? value)
    nil

    ;; Strings get truncated
    (string? value)
    (if (> (count value) max-string-len)
      (str (subs value 0 max-string-len) ellipsis)
      value)

    ;; Keywords, symbols, numbers, booleans pass through
    (or (keyword? value)
        (symbol? value)
        (number? value)
        (boolean? value)
        (instance? java.util.UUID value))
    value

    ;; Maps - recursively process values
    (map? value)
    (into {}
          (map (fn [[k v]]
                 [k (truncate-value v opts (inc current-depth))]))
          value)

    ;; Sets - convert to vector and process
    (set? value)
    (let [items (vec value)
          truncated-items (if (> (count items) max-coll-items)
                           (concat (take max-coll-items items)
                                  [(str "<" (- (count items) max-coll-items) " more items>")])
                           items)]
      (set (map #(if (string? %)
                  %
                  (truncate-value % opts (inc current-depth)))
               truncated-items)))

    ;; Vectors
    (vector? value)
    (let [truncated-items (if (> (count value) max-coll-items)
                           (concat (take max-coll-items value)
                                  [(str "<" (- (count value) max-coll-items) " more items>")])
                           value)]
      (mapv #(truncate-value % opts (inc current-depth)) truncated-items))

    ;; Lists and sequences (including LazySeq)
    (sequential? value)
    (let [realized-items (take (inc max-coll-items) value)
          items-count (count realized-items)
          has-more? (> items-count max-coll-items)
          truncated-items (if has-more?
                           (concat (take max-coll-items realized-items)
                                  ["<more items>"])
                           realized-items)]
      (map #(truncate-value % opts (inc current-depth)) truncated-items))

    ;; For any other type, convert to string and truncate
    :else
    (let [str-value (str value)]
      (if (> (count str-value) max-string-len)
        (str (subs str-value 0 max-string-len) ellipsis)
        str-value))))

;; ============================================================================
;; Specialized truncation helpers
;; ============================================================================

(defn truncate-string
  "Truncate a single string to specified length"
  ([s] (truncate-string s 100))
  ([s max-len]
   (if (and (string? s) (> (count s) max-len))
     (str (subs s 0 max-len) "...")
     s)))

(defn truncate-collection
  "Truncate a collection to specified number of items"
  ([coll] (truncate-collection coll 10))
  ([coll max-items]
   (if (> (count coll) max-items)
     (concat (take max-items coll)
            [(str "<" (- (count coll) max-items) " more items>")])
     coll)))

(defn safe-pr-str
  "Safely convert value to string with truncation for logging"
  ([value]
   (safe-pr-str value {}))
  ([value opts]
   (pr-str (truncate-for-logging value opts))))

;; ============================================================================
;; RAG-specific logging with Telemere
;; ============================================================================

(def ^:private rag-default-opts
  "More aggressive truncation defaults for RAG pipeline data.
   RAG outputs tend to have long text content and many chunks."
  {:max-string-len 200
   :max-coll-items 5
   :max-depth 6
   :ellipsis "..."})

(def ^:dynamic *rag-log-opts*
  "Dynamic var for customizing RAG log truncation options per-thread.
   Bind this to override defaults in specific contexts."
  rag-default-opts)

(defn set-rag-log-opts!
  "Set global RAG logging options. Returns the previous options."
  [opts]
  (let [prev *rag-log-opts*]
    (alter-var-root #'*rag-log-opts* (constantly (merge rag-default-opts opts)))
    prev))

(defn- truncate-log-data
  "Truncate the data map in a Telemere log vector.
   Preserves the event keyword, truncates the data map."
  [log-vec opts]
  (if (and (vector? log-vec) (>= (count log-vec) 2))
    (let [[event-key data & rest] log-vec
          truncated-data (if (map? data)
                           (truncate-for-logging data opts)
                           data)]
      (into [event-key truncated-data] rest))
    log-vec))

(defn log!
  "Log with automatic truncation for RAG data.

   Wraps taoensso.telemere/log! with automatic truncation of the data map.
   Uses RAG-optimized defaults that are more aggressive than general logging.

   Usage:
     (log! :info [:event/name {:key value}])
     (log! :info [:event/name {:key value}] {:max-string-len 100})

   The optional third argument overrides truncation options:
     :max-string-len  - Max chars for strings (default 200)
     :max-coll-items  - Max items in collections (default 5)
     :max-depth       - Max recursion depth (default 6)"
  ([level log-vec]
   (log! level log-vec {}))
  ([level log-vec opts]
   (let [effective-opts (merge *rag-log-opts* opts)
         truncated-vec (truncate-log-data log-vec effective-opts)]
     (t/log! level truncated-vec))))

(defmacro log-info!
  "Log at INFO level with automatic RAG data truncation."
  ([log-vec]
   `(log! :info ~log-vec))
  ([log-vec opts]
   `(log! :info ~log-vec ~opts)))

(defmacro log-warn!
  "Log at WARN level with automatic RAG data truncation."
  ([log-vec]
   `(log! :warn ~log-vec))
  ([log-vec opts]
   `(log! :warn ~log-vec ~opts)))

(defmacro log-error!
  "Log at ERROR level with automatic RAG data truncation."
  ([log-vec]
   `(log! :error ~log-vec))
  ([log-vec opts]
   `(log! :error ~log-vec ~opts)))

(defmacro log-debug!
  "Log at DEBUG level with automatic RAG data truncation."
  ([log-vec]
   `(log! :debug ~log-vec))
  ([log-vec opts]
   `(log! :debug ~log-vec ~opts)))
