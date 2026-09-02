(ns digdir.import-export.format.jsonl
  "JSON Lines serialization for large/deeply-nested entities (one JSON object
   per line). Streams to/from disk; suitable for collections like conversations
   that nest several levels deep and contain large text blobs.

   Namespaced keys round-trip via cheshire — `:conversation/id` writes as
   `\"conversation/id\"` and parses back to `:conversation/id`."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn write-records
  "Write `records` as JSONL to `dest` (path, File, or io/writer target)."
  [dest records]
  (with-open [w (io/writer dest)]
    (doseq [record records]
      (.write w ^String (json/generate-string record))
      (.write w "\n"))))

(defn read-records
  "Read JSONL from `source` (path, File, or io/reader source) into a vector of
   records. Keys are parsed as keywords (namespaced keys preserved). Blank
   lines are skipped."
  [source]
  (with-open [r (io/reader source)]
    (->> (line-seq r)
         (remove str/blank?)
         (mapv #(json/parse-string % true)))))
