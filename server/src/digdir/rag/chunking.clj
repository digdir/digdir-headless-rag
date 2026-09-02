(ns digdir.rag.chunking
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.spec.alpha :as s]))

(defn- rag-debug-logging-enabled? []
  (let [env-val (System/getenv "RAG_DEBUG_LOGGING")]
    (contains? #{"1" "true" "yes" "on"}
               (str/lower-case (str (or env-val ""))))))

(defn- rag-debug-log [msg data]
  (when (rag-debug-logging-enabled?)
    (log/info msg data)))

;; SOURCE: https://dev.to/oleh-halytskyi/optimizing-rag-context-chunking-and-summarization-for-technical-docs-3pel#split-markdown-into-chunks-based-on-headers

;; Configuration vars
(def ^:private include-headers-in-content? false)
(def ^:private filter-headers #{"Table of Contents" "This Page" "Navigation"})
(def ^:private show-unwanted-chunks-metadata? false)

(defn- header-line?
  "Check if a line is a markdown header."
  [line]
  (re-matches #"^(#{1,6})\s+(.*)" line))

(defn- code-block-line?
  "Check if a line is a code block delimiter."
  [line]
  (re-matches #"^\s*```" line))

(defn- clean-header-text
  "Clean header text by removing backslashes and paragraph links."
  [text]
  (-> text
      (str/replace #"\\" "")
      (str/replace #"\[¶\]\(.*?\)" "")
      str/trim))

(defn- create-document
  "Create a document map with content and metadata."
  [content metadata]
  {:page-content content
   :metadata metadata})

(defn concatenate-too-small-chunks [kview chunks]
  (let [min-length (:chunks/minimum-length kview)
        ;; Identify groups of consecutive chunks where concatenation is needed
        groups (loop [idx 0
                      groups []
                      current-group []]
                 (if (>= idx (count chunks))
                   (if (empty? current-group)
                     groups
                     (conj groups current-group))
                   (let [chunk (nth chunks idx)
                         chunk-length (count (:page-content chunk))]
                     (if (< chunk-length min-length)
                       (recur (inc idx) groups (conj current-group idx))
                       (if (empty? current-group)
                         (recur (inc idx) groups current-group)
                         (recur (inc idx) 
                                (conj groups (conj current-group idx))
                                []))))))
        ;; Helper function to concatenate a group of chunks
        concatenate-group (fn [indices]
                            (let [group-chunks (map #(nth chunks %) indices)
                                  combined-content (->> group-chunks
                                                        (map :page-content)
                                                        (str/join "\n\n"))
                                  ;; ⚠️ `(or {})` USED TO BE THE LAST FORM OF THIS
                                  ;; THREAD, AND IT ALWAYS WON. `->>` expands it
                                  ;; to `(or {} <the-metadata-we-found>)`, and an
                                  ;; empty map is TRUTHY in Clojure — so the
                                  ;; fallback returned before the value it was
                                  ;; guarding was ever considered. Every
                                  ;; concatenated chunk carried `{}`, losing the
                                  ;; headers entirely rather than merely naming
                                  ;; one section of several. Do not re-thread it.
                                  combined-metadata (or (->> group-chunks
                                                             (map :metadata)
                                                             (filter seq)
                                                             first)
                                                        {})]
                              {:page-content combined-content
                               :metadata combined-metadata}))
        ;; Create a set of indices that are part of groups
        grouped-indices (set (apply concat groups))]
    ;; Process chunks, concatenating groups and keeping ungrouped chunks as-is
    (loop [idx 0
           processed []]
      (cond
        (>= idx (count chunks))
        processed
        
        (grouped-indices idx)
        (let [group (first (filter #(some #{idx} %) groups))]
          (if (= idx (first group))
            (recur (+ idx (count group))
                   (conj processed (concatenate-group group)))
            (recur (inc idx) processed)))
        
        :else
        (recur (inc idx) 
               (conj processed (nth chunks idx)))))))

;; ---------------------------------------------------------------------------
;; Lever B — sub-split oversized chunks to a reranker-sized maximum. Header-based
;; splitting alone caps NOTHING (a header-section with no sub-headers becomes one
;; chunk of any size), producing the 6k–22k-char goldens that ColBERT truncates.
;; This pass breaks those into <=max paragraph-aware sub-chunks so every chunk is
;; reranker-sized, removing the long-chunk advantage at the source. Chunks already
;; within max pass through byte-identical (=> identical chunk_id downstream).
;; ---------------------------------------------------------------------------

(defn- hard-windows
  "Last-resort: split a single over-long segment into <=max char pieces."
  [s max]
  (loop [s s acc []]
    (if (<= (count s) max)
      (conj acc s)
      (recur (subs s max) (conj acc (subs s 0 max))))))

(defn- atomize
  "Break content into atomic segments each <= max: paragraphs first, then (for an
   over-long paragraph) sentences, then a hard char split as the floor."
  [content max]
  (->> (str/split content #"\n{2,}")
       (remove str/blank?)
       (mapcat (fn [p]
                 (if (<= (count p) max)
                   [p]
                   (->> (str/split p #"(?<=[.!?])\s+")
                        (remove str/blank?)
                        (mapcat #(if (<= (count %) max) [%] (hard-windows % max)))))))))

(defn- tail-overlap
  "Last ~overlap chars of s, snapped forward to a word boundary so the carried
   context starts mid-word as little as possible."
  [s overlap]
  (if (or (<= overlap 0) (<= (count s) overlap))
    (when (pos? overlap) s)
    (let [t (subs s (- (count s) overlap))
          sp (str/index-of t " ")]
      (if sp (subs t (inc sp)) t))))

(defn- pack-segments
  "Greedily pack atomic segments into <=max sub-chunks joined by blank lines,
   carrying ~overlap chars of trailing context from each full sub-chunk into the
   next so a boundary-spanning answer survives."
  [segs max overlap]
  (loop [segs segs cur "" out []]
    (if (empty? segs)
      (if (str/blank? cur) out (conj out cur))
      (let [seg (first segs)
            candidate (if (str/blank? cur) seg (str cur "\n\n" seg))]
        (if (<= (count candidate) max)
          (recur (rest segs) candidate out)
          (let [ov (tail-overlap cur overlap)
                start (if (str/blank? ov) seg (str ov "\n\n" seg))]
            (recur (rest segs) start (conj out cur))))))))

(defn split-oversized-content
  "Vector of <=~max sub-content strings for one chunk's content (single-element
   [content] when it already fits — so short chunks are byte-identical)."
  [content max overlap]
  (if (<= (count content) max)
    [content]
    (pack-segments (atomize content max) max overlap)))

(defn split-oversized-chunks
  "Replace each chunk whose :page-content exceeds max-length with <=max-length
   paragraph-aware sub-chunks (each carrying ~overlap chars from the prior one)
   inheriting the parent :metadata. Chunks within max pass through unchanged.
   nil/0 max-length is a no-op (preserves the pre-Lever-B behavior)."
  [max-length overlap chunks]
  (if (and max-length (pos? max-length))
    (vec (mapcat (fn [chunk]
                   (let [parts (split-oversized-content (:page-content chunk) max-length (or overlap 0))]
                     (if (<= (count parts) 1)
                       [chunk]
                       (mapv #(assoc chunk :page-content %) parts))))
                 chunks))
    chunks))

(defn split-into-chunks-by-headers
  "Divide Markdown documents into chunks based on headers.
   Takes a sequence of documents with :page-content.
   Returns a sequence of documents with :page-content and :metadata."
  [kview md-docs]
  (let [process-doc
        (fn [doc]
          (let [lines (str/split-lines (:page-content doc))
                process-chunks
                (fn [lines]
                  (loop [remaining-lines lines
                         chunks []
                         current-chunk {:metadata {} :content ""}
                         current-headers {}
                         prev-header-level 0
                         in-code-block? false]
                    (if (empty? remaining-lines)
                      (if (not-empty (:content current-chunk))
                        (conj chunks (update current-chunk :content str/trim))
                        chunks)
                      (let [line (first remaining-lines)
                            code-block? (code-block-line? line)
                            next-in-code-block? (if code-block? (not in-code-block?) in-code-block?)
                            header-match (when-not in-code-block? (header-line? line))]
                        (if header-match
                          (let [[_ hashes header-text] (re-matches #"^(#{1,6})\s+(.*)" line)
                                header-level (count hashes)
                                clean-text (clean-header-text header-text)
                                header-key (str "Header " header-level)
                                new-headers (cond-> current-headers
                                              (> header-level prev-header-level) (assoc header-key clean-text)
                                              (<= header-level prev-header-level) (-> (dissoc (str "Header " prev-header-level))
                                                                                      (assoc header-key clean-text)))
                                new-chunk (cond-> {:metadata new-headers :content ""}
                                            include-headers-in-content? (update :content str line "\n"))]
                            (recur (rest remaining-lines)
                                   (if (not-empty (:content current-chunk))
                                     (conj chunks (update current-chunk :content str/trim))
                                     chunks)
                                   new-chunk
                                   new-headers
                                   header-level
                                   next-in-code-block?))
                          (recur (rest remaining-lines)
                                 chunks
                                 (update current-chunk :content str line "\n")
                                 current-headers
                                 prev-header-level
                                 next-in-code-block?))))))]
            (process-chunks lines)))
        all-chunks (mapcat process-doc md-docs)
        should-filter? (fn [chunk]
                         (let [metadata (:metadata chunk)]
                           (boolean
                            (and (not-empty metadata)
                                 (some (fn [header-text]
                                         (some #(str/includes? header-text %)
                                               filter-headers))
                                       (vals metadata))))))
        grouped (group-by should-filter? all-chunks)
        documents (get grouped false [])
        unwanted (get grouped true [])]
    (rag-debug-log "chunking all-chunks created" {:count (count all-chunks)})
    (when (rag-debug-logging-enabled?)
      (doseq [chunk all-chunks]
        (rag-debug-log "chunk metadata" {:metadata (:metadata chunk)})))
    (rag-debug-log "chunking grouped" {:documents (count documents)
                                       :unwanted (count unwanted)})
    (when (and show-unwanted-chunks-metadata? (seq unwanted))
      (rag-debug-log "unwanted chunks metadata"
                     {:metadata (mapv :metadata unwanted)}))
    (->> (mapv #(create-document (:content %) (:metadata %)) documents)
         (concatenate-too-small-chunks kview)
         ;; Lever B: optional sub-split to a reranker-sized max. Absent key =>
         ;; no-op (pre-Lever-B behavior preserved).
         (split-oversized-chunks (:chunks/split-max-length kview)
                                 (:chunks/overlap kview 0)))))

;; Spec definitions for validation
(s/def ::page-content string?)
(s/def ::metadata map?)
(s/def ::document (s/keys :req-un [::page-content ::metadata]))
(s/def ::md-docs (s/coll-of ::document))


(comment
  ;;
  (require '[clojure.java.io :as jio])

  (def test-md
    (let [cache-dir "cache/md/"
          md-files (filter #(.endsWith (.getName %) ".md") (file-seq (jio/file cache-dir)))]
      (if (> (count md-files) 1)
        (slurp (first md-files))
        (throw (Exception. (str "Expected at least one .md file in " cache-dir ", found " (count md-files)))))))
  
  ;;
  )
