(ns digdir.import-export.format.yaml
  "YAML serialization for namespaced entity records using a natural-key map shape.

   Each file holds one entity collection: a `_namespace` declaration plus a
   single collection key whose value is a map of records keyed by their natural
   identifier. Top-level keys whose namespace matches `_namespace` are stripped
   for readability; nested or differently-namespaced keys pass through.

   Secret fields are encrypted with digdir.config.crypto (AES-256-GCM) and
   stored with an `enc:` prefix; readers decrypt automatically given the
   master key."
  (:refer-clojure :exclude [read])
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [digdir.config.crypto :as crypto]
            [flatland.ordered.map :as om]))

(def ^:private secret-prefix "enc:")

(defn- encrypt-secret [v master-key]
  (str secret-prefix (crypto/encrypt v master-key)))

(defn- decrypt-secret [v master-key]
  (crypto/decrypt (subs v (count secret-prefix)) master-key))

(defn- secret-string? [v]
  (and (string? v) (str/starts-with? v secret-prefix)))

(defn- strip-ns
  [k target-ns]
  (if (and (keyword? k) (= (namespace k) target-ns))
    (keyword (name k))
    k))

(defn- attach-ns
  [k target-ns]
  (if (and (keyword? k) (nil? (namespace k)))
    (keyword target-ns (name k))
    k))

(defn key->string
  "Render a YAML map key as the string the user originally wrote.

   clj-yaml parses a key like `runtime/acme/root` as the keyword
   :runtime/acme/root (split at the first slash), so calling `name` on it
   would lose the `runtime/` prefix. Stringify and strip the leading colon
   instead — round-trips slash-bearing identifiers intact."
  [k]
  (cond
    (string? k) k
    (keyword? k) (subs (str k) 1)
    :else (str k)))

(defn- encode-record [record target-ns secret-keys drop-keys master-key]
  (reduce (fn [acc [k v]]
            (if (contains? drop-keys k)
              acc
              (let [k' (strip-ns k target-ns)
                    v' (if (contains? secret-keys k)
                         (encrypt-secret v master-key)
                         v)]
                (assoc acc k' v'))))
          (om/ordered-map)
          record))

(defn- decode-record [record target-ns master-key]
  (reduce (fn [acc [k v]]
            (let [k' (attach-ns k target-ns)
                  v' (if (secret-string? v)
                       (do (when-not master-key
                             (throw (ex-info "encrypted value but no master-key supplied"
                                             {:hint "set CONFIG_MASTER_KEY"})))
                           (decrypt-secret v master-key))
                       v)]
              (assoc acc k' v')))
          {}
          record))

(defn write
  "Serialize `records` to a YAML string.

   Required opts:
     :namespace      e.g. \"config-def\" — the file's primary namespace
     :collection-key e.g. :defs — top-level key holding the record map
     :key-fn         record -> string identifier (natural key)

   Optional opts:
     :drop-keys      set of keys to omit from each record's body. Use to drop
                     a field that's already represented as the natural key.
     :secret-keys    set of keyword keys whose values are encrypted on write
     :master-key     CONFIG_MASTER_KEY (required if :secret-keys non-empty)"
  [records {:keys [namespace collection-key key-fn drop-keys secret-keys master-key]}]
  (let [secret-keys (or secret-keys #{})
        drop-keys (or drop-keys #{})
        body (reduce
               (fn [acc record]
                 (assoc acc (key-fn record)
                        (encode-record record namespace secret-keys drop-keys master-key)))
               (om/ordered-map)
               records)
        doc (om/ordered-map :_namespace namespace
                            collection-key body)]
    (yaml/generate-string doc :dumper-options {:flow-style :block})))

(defn read
  "Parse a YAML string written by `write` back into records.

   Returns:
     {:namespace string
      :collection-key keyword
      :records [...]}   ; records have namespaced keys reattached and
                        ; secret values decrypted

   Optional opts:
     :master-key      CONFIG_MASTER_KEY (required if file contains enc: values)
     :reinject-key    keyword — if set, each record gets this field added with
                      the value of its natural-key (the YAML map key). Use
                      symmetrically with `write`'s `:drop-keys` to round-trip
                      the natural-key field without storing it twice."
  [content {:keys [master-key reinject-key]}]
  (let [parsed (yaml/parse-string content :keywords true)
        target-ns (some-> (:_namespace parsed) name)
        coll-key (first (remove #{:_namespace} (keys parsed)))
        body (get parsed coll-key)
        records (vec (for [[natural-key record] body]
                       (cond-> (decode-record record target-ns master-key)
                         reinject-key (assoc reinject-key (key->string natural-key)))))]
    {:namespace target-ns
     :collection-key coll-key
     :records records}))
