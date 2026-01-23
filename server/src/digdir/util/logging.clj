(ns digdir.util.logging
  "Utilities for safe and efficient logging of complex data structures"
  (:require [clojure.string :as str]))

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
