(ns digdir.config.ops.util
  (:require [digdir.config.crypto :as crypto]))

(def export-version "1.0")

(def encryption-metadata
  {:method "aes-256-gcm"
   :key-derivation "pbkdf2-sha256"
   :iterations 100000})

(defn clean-db-pipeline
  "Remove internal Datahike fields from an pipeline map."
  [pipeline]
  (dissoc pipeline :db/id))

(defn keyword->string
  "Convert keyword to string for JSON serialization."
  [k]
  (if (keyword? k)
    (subs (str k) 1)
    k))

(defn serialize-pipeline
  "Serialize an pipeline for JSON export.
   Converts keywords to strings for JSON compatibility."
  [pipeline]
  (reduce-kv
   (fn [acc k v]
     (let [str-key (keyword->string k)
           str-val (cond
                     (keyword? v) (keyword->string v)
                     (map? v) (serialize-pipeline v)
                     :else v)]
       (assoc acc str-key str-val)))
   {}
   pipeline))

(defn re-encrypt-config-value
  "Re-encrypt a stored config value from one key to another."
  [encrypted-value source-key target-key]
  (crypto/re-encrypt encrypted-value source-key target-key))
