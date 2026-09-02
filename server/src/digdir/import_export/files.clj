(ns digdir.import-export.files
  "Thin JSON file wrappers for import/export edges."
  (:require [clojure.data.json :as json]
            [clojure.walk :as walk]))

(defn- json-key
  [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)]
                   (str ns "/" (name k))
                   (name k))
    (symbol? k) (name k)
    :else k))

(defn- json-encode
  [data]
  (walk/postwalk (fn [node]
                   (if (map? node)
                     (into {} (map (fn [[k v]] [(json-key k) v])) node)
                     node))
                 data))

(defn read-json-file
  [file-path]
  (json/read-str (slurp file-path) :key-fn keyword))

(defn write-json-file!
  [file-path data]
  (spit file-path (json/write-str (json-encode data) :escape-slash false))
  file-path)
