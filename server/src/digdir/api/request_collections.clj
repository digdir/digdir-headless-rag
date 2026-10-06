(ns digdir.api.request-collections
  "a request may name a Typesense collection only if it belongs to the
   dataset the request resolved to.

   `POST /api/skills/:id/execute` hands the client's `inputs` and `parameters` to
   the skill unchanged, and skills honour an explicit collection name over the
   dataset's (`skills/context.clj`: \"explicit inputs win\"; retrieval's
   `:enrichment-search-targets` parameter). Without this check a key scoped to
   one dataset could make the server search, and through
   `enrichment-apply-questions` delete from and write to, any collection on the
   tenant's Typesense - including another dataset's that the same key is refused.

   The check does not know which skill will read which key. It walks the WHOLE of
   `inputs` and `parameters`, at any depth, and every string under a key that
   names a collection must be one of the dataset's own. So a skill added later,
   or a key nested under a sub-skill, is covered without being listed."
  (:require [clojure.string :as str]
            [digdir.skills.enrichment.naming :as enrichment-naming]))

(def ^:private not-a-collection-name
  "Keys whose name contains \"collection\" but whose value is not a collection
   name. Normalised as `names-collection?` normalises."
  #{"collectionprefix" "collectionkey"})

(defn- normalise-key [k]
  (-> (if (keyword? k) (name k) (str k))
      str/lower-case
      (str/replace #"[-_]" "")))

(defn names-collection?
  "Whether a request key's value names Typesense collections. Case- and
   separator-insensitive: `:chunks-collection`, `chunksCollection` and
   `chunks_collection` are one key to a client."
  [k]
  (let [n (normalise-key k)]
    (or (= n "enrichmentsearchtargets")
        (and (str/includes? n "collection")
             (not (contains? not-a-collection-name n))))))

(defn- strings-in [v]
  (cond
    (string? v) [v]
    (map? v) (mapcat strings-in (vals v))
    (coll? v) (mapcat strings-in v)
    :else []))

(defn keys-in
  "THE walker: every `[key-path value]` in `data`, at any depth, whose key
   satisfies `pred`. A matched key's value is not descended into. Both request
   checks below use it (the tenant-scope fix extends the request collection-names fix's check, it does not add a walker)."
  ([pred data] (keys-in pred data []))
  ([pred data path]
   (cond
     (map? data)
     (mapcat (fn [[k v]]
               (if (pred k)
                 [[(conj path k) v]]
                 (keys-in pred v (conj path k))))
             data)

     (coll? data)
     (mapcat (fn [[i v]] (keys-in pred v (conj path i))) (map-indexed vector data))

     :else [])))

(defn named-collections
  "Every `[key-path name]` in `data` whose name sits under a collection-naming key,
   at any depth."
  ([data] (named-collections data []))
  ([data path]
   (mapcat (fn [[p v]] (map (fn [s] [p s]) (strings-in v))) (keys-in names-collection? data path))))

(def ^:private identity-keys
  "the keys that say WHICH tenant, dataset,
   config node or runtime a call acts in. Normalised as `names-collection?`
   normalises, so every spelling is one key. `tenant-config-key` is the legacy
   alias of the dataset key (`execution.scope/normalize-dataset-ref`)."
  #{"tenant" "datasetid" "datasetconfigkey" "tenantconfigkey" "datasetref" "runtimeconfigkey" "nodeid"})

(defn names-identity?
  "Whether a request key names the scope a call acts in (`identity-keys`)."
  [k]
  (contains? identity-keys (normalise-key k)))

(defn check-request-identity!
  "throw a 400 `:identity-in-request` if `inputs` or `parameters` of
   an execute request carry an identity key, at any depth. A skill's tenant,
   dataset and credentials come only from the AUTHORIZED scope the door
   resolved, never from what the request hands the skill: a key granted one
   tenant could otherwise move the tenant into `inputs` and make the server use
   another tenant's Typesense host and admin key. Returns nil otherwise."
  [{:keys [inputs parameters]}]
  (let [found (concat (keys-in names-identity? inputs [:inputs])
                      (keys-in names-identity? parameters [:parameters]))]
    (when-let [[path] (first found)]
      (throw (ex-info (str "The request's " (pr-str path) " names a tenant, dataset or config node. A skill acts only "
                           "in the dataset the request selects (its top-level `tenant` and `dataset-config-key`); "
                           "inputs and parameters cannot choose another.")
                      {:status 400
                       :reason :identity-in-request
                       :path path
                       :found (mapv first found)})))))

(defn dataset-collections
  "The collection names a request on `dataset-context` may name: its three
   collections, and the enrichment collections derived from its chunks name.
   A derivation that returns the chunks name unchanged (a non-conventional name)
   adds nothing."
  [dataset-context]
  (let [{:keys [docs-collection chunks-collection phrases-collection]} (:dataset-inputs dataset-context)
        own (set (remove str/blank? [docs-collection chunks-collection phrases-collection]))]
    (into own
          (when-not (str/blank? chunks-collection)
            (->> enrichment-naming/enrichment-types
                 (map #(enrichment-naming/enrichment-collection-name-from-base chunks-collection %))
                 (remove #{chunks-collection}))))))

(defn check-request-collections!
  "Throw a 400 if `inputs` or `parameters` of a request name a collection outside
   `dataset-context`'s own. Returns nil otherwise."
  [dataset-context {:keys [inputs parameters]}]
  (let [allowed (dataset-collections dataset-context)
        refused (->> (concat (named-collections inputs [:inputs])
                             (named-collections parameters [:parameters]))
                     (remove (fn [[_ n]] (contains? allowed n))))]
    (when-let [[path n] (first refused)]
      (throw (ex-info (str "Collection \"" n "\" (at " (pr-str path) ") is not one of the requested dataset's "
                           "collections. A request may only name its own dataset's collections.")
                      {:status 400
                       :reason :collection-not-in-dataset
                       :collection n
                       :path path
                       :refused-count (count refused)})))))
