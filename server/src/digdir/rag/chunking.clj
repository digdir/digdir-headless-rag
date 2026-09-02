(ns digdir.rag.chunking
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]))

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
                                  combined-metadata (->> group-chunks
                                                         (map :metadata)
                                                         (filter seq)
                                                         first
                                                         (or {}))]
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

(defn split-into-chunks-by-headers
  "Divide Markdown documents into chunks based on headers.
   Takes a sequence of documents with :page-content.
   Returns a sequence of documents with :page-content and :metadata."
  [kview md-docs]
  (->> (let [process-doc
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
                 (process-chunks lines)))]
         
         (let [all-chunks (mapcat process-doc md-docs)
               _ (println "All chunks created:" (count all-chunks))
               _ (doseq [chunk all-chunks]
                   (println "Chunk metadata:" (:metadata chunk)))
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
           (println "Documents:" (count documents))
           (println "Unwanted:" (count unwanted))
           
           (when (and show-unwanted-chunks-metadata? (seq unwanted))
             (println "Unwanted chunks metadata:")
             (run! (comp println :metadata) unwanted)
             (println))
           
           (mapv #(create-document (:content %) (:metadata %)) documents)))
       (concatenate-too-small-chunks kview)))

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
