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
   Used for document IDs, cache keys, and deduplication.

   COLLISION BOUND, because these ids are Typesense primary keys and a
   collision is not a mislabelling — the second upsert deletes the first.
   12 hex characters is 48 bits, so for `n` ids sharing ONE keyspace the
   chance of at least one accidental collision is ~`1 - e^(-n^2/2^49)`,
   reaching even odds at ~19.8 million.

   Bound the population you are actually hashing, not the corpus. These are
   separate keyspaces and they differ by ~580x (measured #72, 2026-08-21):

     documents     11,306 ids -> ~0.000%  (~1750x headroom)
     chunks       713,923 ids -> ~0.09%   (~28x   headroom)
     phrases    6,564,478 ids -> ~7.4%    (~3x    headroom)  <- BINDING

   PHRASES IS THE CONSTRAINT, not chunks. Phrase ids are
   `sha256-short-hash(chunk_id|phrase)`, roughly 9 per chunk, so that
   keyspace is an order of magnitude denser than the one people reach for
   when they think about this. If phrase rows approach ~20 million, widen
   the truncation here before scaling the corpus.

   This is a *different* failure from the one #72/04d0bec fixed. That was
   systematic — content-only ids collided across documents, clustered in
   boilerplate, erasing whole documents. This one is random and isolated:
   it costs single rows, never a document."
  [v]
  (->> v valuehash.api/sha-256-str (take 12) (apply str)))

(defn chunk-id
  "Derive a chunk's durable id from the document it belongs to, the chunk
   text, and how many identical chunks precede it *within that document*.

   `chunk_id` is the Typesense primary key for the chunks collection, and
   phrase ids are derived from it in turn, so two chunks sharing an id are
   not merely mislabelled — the second upsert deletes the first. Hashing
   content alone made that happen whenever two documents contained the same
   text, which in this corpus is ordinary: boilerplate sections recur across
   annual reports, and measurement on the live index found 9% of chunk rows
   duplicated, spanning 36% of documents (#72).

   Why document + content rather than content + position:

   - Position does not separate the documents that actually collide. The
     clearest measured example was one report registered under two doc_nums,
     sharing its title page at chunk_index 0 in both — identical content at
     an identical position. Content+position would still have collided.
   - Position is unstable under editing. Insert a paragraph near the top of
     a document and every later chunk shifts index, so every later id
     changes: all rows rewritten, all phrase rows rewritten, and the
     search-phrase cache (keyed on chunk_id) missed for the whole tail,
     which costs real LLM calls. Hashing content keeps an unedited chunk's
     id stable no matter what moved around it.

   `occurrence` closes the remaining gap: the same text really can appear
   twice in one document, and document+content alone would collide there.
   It counts identical earlier chunks rather than absolute position, so it
   stays stable when unrelated chunks are inserted or removed.

   Throws when `doc-num` is blank. A missing document id would silently
   collapse the derivation back to content-only and reintroduce exactly the
   data loss this exists to prevent, so it must fail at ingest instead."
  [doc-num occurrence content]
  (when (str/blank? (str doc-num))
    (throw (ex-info "Cannot derive a chunk id without a document id"
                    {:doc-num doc-num
                     :occurrence occurrence
                     :content-length (count (str content))})))
  (sha256-short-hash (str doc-num "|" occurrence "|" content)))

(defn assign-chunk-ids
  "Assoc `:chunk_id` onto every chunk of ONE document, in order.

   Takes the whole seq because the id depends on how many identical chunks
   came before — see `chunk-id`. Chunkers cannot do this themselves: they
   see text, not which document it belongs to."
  [doc-num chunks]
  (first
   (reduce (fn [[acc seen] chunk]
             (let [content (:content_markdown chunk)
                   occurrence (get seen content 0)]
               [(conj acc (assoc chunk :chunk_id (chunk-id doc-num occurrence content)))
                (assoc seen content (inc occurrence))]))
           [[] {}]
           chunks)))

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
