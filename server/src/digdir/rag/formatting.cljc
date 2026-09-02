(ns digdir.rag.formatting
  "Pure formatting helpers for RAG retrieval and rerank flows."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn parse-header-level
  "Parse a header level string to integer, returns nil if invalid"
  [level-str]
  (try
    #?(:clj (Integer/parseInt level-str)
       :cljs (let [n (js/parseInt level-str 10)]
               (when-not (js/isNaN n) n)))
    (catch #?(:clj Exception :cljs js/Error) _
      nil)))

(defn format-header
  "Format a single header with the specified level"
  [level content]
  (str (str/join (repeat level "#")) " " content))

(defn extract-header-entries
  "Extract and sort header entries from a metadata map"
  [metadata-map]
  (if (map? metadata-map)
    (->> metadata-map
         (keep (fn [[k v]]
                 (let [k-str (cond
                               (string? k) k
                               (keyword? k) (name k)
                               :else (str k))
                       header-match (re-matches #"Header (\d+)" k-str)]
                   (when header-match
                     (let [level-str (second header-match)]
                       (when-let [level (parse-header-level level-str)]
                         (when (pos? level)
                           [(min 4 (+ 2 level)) v])))))))
         (into []))
    []))

(defn format-metadata-headers
  "Formats metadata as markdown headers. If metadata is a map with 'Header N' keys,
  converts it to markdown headers (e.g., '# Title' for 'Header 1'). Otherwise,
  returns nil."
  [metadata]
  (let [metadata-map (cond
                       (string? metadata) (try
                                            (let [parsed (edn/read-string metadata)]
                                              (if (string? parsed)
                                                (edn/read-string parsed)
                                                parsed))
                                            (catch #?(:clj Exception :cljs js/Error) _
                                              nil))
                       (map? metadata) metadata
                       :else nil)]
    (when (map? metadata-map)
      (let [header-entries (extract-header-entries metadata-map)
            sorted-entries (sort-by first header-entries)]
        (when (seq sorted-entries)
          (str/join "\n"
                    (map (fn [[level content]]
                           (format-header level content))
                         sorted-entries)))))))

(defn truncate-head-tail
  "Truncate long text by keeping both head and tail segments.
   This preserves late-document evidence that is often lost with head-only truncation."
  [s max-len]
  (let [s (or s "")
        max-len (max 0 (or max-len 0))]
    (if (<= (count s) max-len)
      s
      (let [marker "\n...\n"
            marker-len (count marker)]
        (if (<= max-len (+ marker-len 2))
          (subs s 0 max-len)
          (let [budget (- max-len marker-len)
                head-len #?(:clj (int (Math/ceil (/ budget 2.0)))
                            :cljs (js/Math.ceil (/ budget 2.0)))
                tail-len (- budget head-len)]
            (str (subs s 0 head-len)
                 marker
                 (subs s (- (count s) tail-len)))))))))
