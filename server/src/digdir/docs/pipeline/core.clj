(ns digdir.docs.pipeline.core
  "Shared utility functions for document processing pipelines.

   This namespace contains core utilities used across all document sources:
   - Hashing functions for deduplication and caching
   - Function composition helpers
   - Retry logic with exponential backoff
   - Date/time utilities"
  (:require [valuehash.api]
            [taoensso.telemere :as t]
            [clojure.string :as str]))

;; ============================================================================
;; Hashing Utilities
;; ============================================================================

(defn sha256-short-hash
  "Generates a 12-character SHA256 hash of any value.
   Used for document IDs, cache keys, and deduplication."
  [v]
  (->> v valuehash.api/sha-256-str (take 12) (apply str)))

;; ============================================================================
;; Function Composition
;; ============================================================================

(defn =>
  "Left-to-right function composition (opposite of comp).
   (=> f g h) is equivalent to (fn [x] (h (g (f x))))

   Example:
     (=> inc #(* % 2)) ; first increment, then double"
  [& fns]
  (apply comp (reverse fns)))

;; ============================================================================
;; Logging/Telemetry Helpers
;; ============================================================================

(defn say
  "Emits a :say telemetry event with a message.
   Used for user-facing progress messages."
  [msg]
  (t/event! :say {:data {:msg msg}})
  nil)

(defn run-task-async
  "Runs a Missionary task asynchronously, logging success/failure via telemetry.
   Returns the cancel function from the task."
  [task]
  (task
   (fn [success] (t/event! :run-task-async-success {:data success}))
   (fn [failure] (t/error! :run-task-async-failure failure))))

;; ============================================================================
;; Retry Logic
;; ============================================================================

(defn exponential-backoff
  "Generates a sequence of n exponentially increasing delays (in ms).
   Each delay is multiplied by 4 and has random jitter (+0-50%).

   Starting at 1000ms:
   - 1st: ~1000-1500ms
   - 2nd: ~4000-6000ms
   - 3rd: ~16000-24000ms
   etc."
  [n]
  (take n
        (map #(+ % (* % (rand 0.5)))
             (iterate (partial * 4) 1000))))

(defn worth-retrying?
  "Determines if an exception is worth retrying.
   Returns true for transient errors like network issues."
  [ex]
  (let [msg (str ex)]
    (or (str/includes? msg "Connection refused")
        (str/includes? msg "Connection reset")
        (str/includes? msg "timeout")
        (str/includes? msg "HTTP/1.1 0")
        (str/includes? msg "503")
        (str/includes? msg "429"))))

;; ============================================================================
;; Path/URL Utilities
;; ============================================================================

(defn split-url-extension
  "Splits a URL/path into [base extension] parts.
   Returns [url nil] if no extension found."
  [url]
  (if-let [match (re-find #"^(.+)\.([^./]+)$" url)]
    [(second match) (last match)]
    [url nil]))

(defn extract-year-from-date
  "Extracts year from various date formats.
   Handles ISO dates, timestamps, and year-only strings."
  [date-str]
  (when date-str
    (if-let [match (re-find #"^(\d{4})" (str date-str))]
      (second match)
      nil)))

;; ============================================================================
;; Document Field Utilities
;; ============================================================================

(defn fill-in-doc-fields
  "Ensures a document has all required fields with defaults.
   doc-type should be :website, :folder, :episerver, or :kudos."
  [doc doc-type]
  (let [type-str (name doc-type)]
    (merge {:id (sha256-short-hash (or (:url doc) (:path doc) (str (random-uuid))))
            :doc_num nil
            :title "Untitled"
            :type type-str
            :lastmod nil}
           doc
           {:doc_num (or (:doc_num doc) (:id doc))})))
