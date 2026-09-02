(ns digdir.playground.citations
  "Citation/source parsing and mapping helpers for Playground chat."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- header-entry-level
  [k]
  (when-let [[_ level] (re-find #"(?i)^header\s+(\d+)$" (str k))]
    (edn/read-string level)))

(defn parse-metadata-map
  "Parse metadata into a map when possible."
  [metadata]
  (cond
    (map? metadata) metadata
    (string? metadata)
    (let [s (str/trim metadata)]
      (when-not (str/blank? s)
        (try
          (let [parsed (edn/read-string s)]
            (when (map? parsed) parsed))
          (catch #?(:clj Exception :cljs :default) _
            nil))))
    :else nil))

(defn metadata-headings
  "Extract ordered heading values from metadata keys like Header 1, Header 2, etc."
  [metadata]
  (let [metadata-map (parse-metadata-map metadata)]
    (->> metadata-map
         (keep (fn [[k v]]
                 (let [level (header-entry-level k)
                       text (str/trim (str (or v "")))]
                   (when (and level (not (str/blank? text)))
                     {:level level :text text}))))
         (sort-by :level)
         (mapv :text))))

(defn source-display-data
  "Build display-first source identity: title + metadata heading breadcrumb."
  ([chunk] (source-display-data chunk nil))
  ([chunk docs-collection-name]
   (let [nested-title (when docs-collection-name
                        (get-in chunk [(keyword docs-collection-name) :title]))
         title (or nested-title (:title chunk))
         title (when (some? title) (str/trim (str title)))
         headings (metadata-headings (:metadata chunk))
         [display-title trailing-headings]
         (cond
           (not (str/blank? title))
           [title headings]

           (seq headings)
           [(first headings) (vec (rest headings))]

           :else
           ["Untitled source" []])]
     {:title display-title
      :headings trailing-headings
      :heading-line (when (seq trailing-headings)
                      (str/join " > " (take 3 trailing-headings)))
      :chunk-id (:chunk_id chunk)})))

(defn citation-source-display-data
  "Resolve display data for a citation, falling back when used-chunk lookup misses."
  ([citation used-chunks]
   (citation-source-display-data citation used-chunks nil))
  ([citation used-chunks docs-collection-name]
   (citation-source-display-data citation used-chunks docs-collection-name nil))
  ([citation used-chunks docs-collection-name fetched-chunk]
   (let [chunk-id (:chunk-id citation)
         chunk    (or fetched-chunk
                      (first (filter #(= chunk-id (:chunk_id %)) (or used-chunks []))))
         fallback {:chunk_id chunk-id
                   :title    (or (:title citation) chunk-id)}]
     (source-display-data (or chunk fallback) docs-collection-name))))

(defn bind-citations
  "Attach resolved source display data to citations."
  ([citations used-chunks]
   (bind-citations citations used-chunks nil))
  ([citations used-chunks docs-collection-name]
   (->> (or citations [])
        (sort-by :index)
        (mapv (fn [citation]
                (assoc citation
                       :source (citation-source-display-data citation
                                                            used-chunks
                                                            docs-collection-name)))))))
