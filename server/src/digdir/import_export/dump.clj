(ns digdir.import-export.dump
  "Dump-based system export/import using YAML for configs and JSONL for
   conversations.

   Layout:
     dumps/<UTC-timestamp>/
       manifest.yaml                ; version + per-file counts
       00-config-defs.yaml          ; defs keyed by path
       01-config-nodes.yaml         ; nodes keyed by id
       02-config-datasets.yaml
       03-config-dataset-pipelines.yaml
       04-config-values/
         <tenant>.yaml              ; 2-level nested: node-id -> def-path -> value
       ...                          ; further entities added over time

   Most entities use the standard single-file natural-key map shape via
   `default-write-fn`/`default-read-fn`. Entities with custom layout
   (config-values, conversations) override `:write-fn` and `:read-fn` on
   their spec."
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [digdir.config.db :as config-db]
            [digdir.config.ops.sync :as config-sync]
            [digdir.import-export.entities.agents :as agents-entity]
            [digdir.import-export.entities.api-keys :as api-keys-entity]
            [digdir.import-export.entities.conversations :as conversations-entity]
            [digdir.import-export.entities.folders :as folders-entity]
            [digdir.import-export.entities.users :as users-entity]
            [digdir.import-export.format.jsonl :as jsonl-fmt]
            [digdir.import-export.format.yaml :as yaml-fmt]
            [flatland.ordered.map :as om])
  (:import [java.io File]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def ^:private dump-version "1.0")

(def ^:private timestamp-formatter
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss'Z'")
      (.withZone ZoneOffset/UTC)))

(defn timestamp-folder-name
  "UTC timestamp suitable as a dump-folder name (sortable, filesystem-safe)."
  []
  (.format timestamp-formatter (Instant/now)))

(defn- sort-fields
  "Reorder map entries by stringified key for deterministic YAML output."
  [m]
  (into (om/ordered-map) (sort-by (comp str first) m)))

(defn- keywordize-record-keys
  "Convert top-level string keys to keywords; existing keyword keys pass through."
  [m]
  (reduce-kv (fn [acc k v]
               (assoc acc (if (string? k) (keyword k) k) v))
             {}
             m))

;; --- Extractors ------------------------------------------------------------

(defn- extract-config-defs
  [config-conn _main-conn _opts]
  (->> (config-db/get-all-definitions @config-conn)
       (mapv #(dissoc % :db/id))))

(defn- canonical-slice
  "Pluck a sub-collection from the cached canonical export and convert
   stringified keys back to keywords for clean YAML emission."
  [opts slice-key]
  (->> (get @(:canonical opts) slice-key)
       (mapv keywordize-record-keys)))

(defn- extract-config-nodes
  [_config-conn _main-conn opts]
  (canonical-slice opts :nodes))

(defn- extract-datasets
  [_config-conn _main-conn opts]
  (canonical-slice opts :datasets))

(defn- extract-dataset-pipelines
  [_config-conn _main-conn opts]
  (canonical-slice opts :dataset-pipelines))

(defn- extract-config-values
  [_config-conn _main-conn opts]
  (canonical-slice opts :node-values))

;; --- config-values (per-tenant, 2-level nested) ----------------------------

(def ^:private values-folder "04-config-values")
(def ^:private value-leaf-keys
  "Fields kept inside the nested leaf — everything except the four routing
   keys (tenant comes from filename, node-id + def-path come from outer/inner
   YAML keys, id is reconstructable)."
  [:config.value/raw
   :config.value/root
   :config.value/created-at
   :config.value/updated-at
   :config.value/deleted-at
   :config.value/pin-of-version])

(defn- strip-value-namespace
  "Remove the :config.value/ namespace from leaf keys for cleaner YAML."
  [m]
  (reduce-kv (fn [acc k v]
               (if (and (keyword? k) (= "config.value" (namespace k)))
                 (assoc acc (keyword (name k)) v)
                 (assoc acc k v)))
             {}
             m))

(defn- value-leaf [v]
  (-> v
      (select-keys value-leaf-keys)
      strip-value-namespace
      sort-fields))

(defn- nest-values-by-tenant
  "Group records into {tenant -> ordered-map(node-id -> ordered-map(def-path -> leaf))}.
   Sort at every level for deterministic output."
  [records]
  (reduce
    (fn [acc [tenant tenant-vals]]
      (let [by-node (->> tenant-vals
                         (group-by :config.value/node-id)
                         (sort-by first))
            outer (reduce
                    (fn [acc* [node-id node-vals]]
                      (let [inner (reduce
                                    (fn [acc** v]
                                      (assoc acc** (:config.value/definition-path v)
                                             (value-leaf v)))
                                    (om/ordered-map)
                                    (sort-by :config.value/definition-path node-vals))]
                        (assoc acc* node-id inner)))
                    (om/ordered-map)
                    by-node)]
        (assoc acc tenant outer)))
    (om/ordered-map)
    (sort-by first (group-by :config.value/tenant records))))

(defn- write-config-values-files!
  [dump-dir _spec records _opts]
  (let [nested (nest-values-by-tenant records)]
    (mapv
      (fn [[tenant outer]]
        (let [filename (str values-folder "/" tenant ".yaml")
              out-file (io/file dump-dir filename)
              doc (om/ordered-map :_namespace "config.value"
                                  :values outer)
              yaml-str (yaml/generate-string doc :dumper-options {:flow-style :block})
              n (reduce + (map count (vals outer)))]
          (io/make-parents out-file)
          (spit out-file yaml-str)
          {:filename filename :count n}))
      nested)))

(defn- attach-value-namespace
  "Re-attach :config.value/ to top-level unqualified keys produced by clj-yaml."
  [m]
  (reduce-kv (fn [acc k v]
               (if (and (keyword? k) (nil? (namespace k)))
                 (assoc acc (keyword "config.value" (name k)) v)
                 (assoc acc k v)))
             {}
             m))

(defn- read-tenant-values-file
  [^File file tenant]
  (let [parsed (yaml/parse-string (slurp file) :keywords true)
        body (:values parsed)]
    (vec
      (for [[node-id-kw def-map] body
            [def-path-kw leaf] def-map]
        (-> leaf
            attach-value-namespace
            (assoc :config.value/tenant tenant
                   :config.value/node-id (yaml-fmt/key->string node-id-kw)
                   :config.value/definition-path (yaml-fmt/key->string def-path-kw)))))))

(defn- read-config-values-files
  [dump-dir _spec _opts]
  (let [folder (io/file dump-dir values-folder)]
    (if-not (.exists folder)
      []
      (->> (.listFiles folder)
           (filter #(str/ends-with? (.getName ^File %) ".yaml"))
           sort
           (mapcat (fn [^File f]
                     (let [tenant (str/replace (.getName f) #"\.yaml$" "")]
                       (read-tenant-values-file f tenant))))
           vec))))

;; --- main-DB / per-entity extractors ---------------------------------------

(defn- extract-users
  [config-conn _main-conn _opts]
  (users-entity/export-users @config-conn))

(defn- qualify-keys-with
  "Add namespace `ns-str` to any unqualified keyword keys in `m`. Used for
   entities (like agents) whose normalized records use unqualified keys."
  [m ns-str]
  (reduce-kv (fn [acc k v]
               (assoc acc
                      (if (and (keyword? k) (nil? (namespace k)))
                        (keyword ns-str (name k))
                        k)
                      v))
             {}
             m))

(defn- unqualify-keys-from
  "Strip namespace `ns-str` from matching keys in `m`. Inverse of qualify-keys-with."
  [m ns-str]
  (reduce-kv (fn [acc k v]
               (assoc acc
                      (if (and (keyword? k) (= ns-str (namespace k)))
                        (keyword (name k))
                        k)
                      v))
             {}
             m))

(defn- extract-agents
  [config-conn _main-conn _opts]
  ;; agents-entity/export-agents returns records with unqualified keys
  ;; (:id, :name, ...). Qualify into the :agent/ namespace so they fit the
  ;; standard YAML format primitive.
  (mapv #(qualify-keys-with % "agent")
        (agents-entity/export-agents @config-conn)))

(defn- extract-folders
  [_config-conn main-conn _opts]
  (folders-entity/export-folders @main-conn))

(defn- sort-api-key-children
  "Datahike pull doesn't guarantee child order; sort nested collections for
   stable diffs across re-exports."
  [api-key]
  (cond-> api-key
    (:api-key/dataset-scopes api-key)
    (update :api-key/dataset-scopes
            (partial sort-by :api-key.dataset-scope/id))

    (:api-key/agent-refs api-key)
    (update :api-key/agent-refs
            (partial sort-by :api-key.agent-ref/id))

    (:api-key/allowed-config-keys api-key)
    (update :api-key/allowed-config-keys
            (partial sort-by :api-key.allowed-config-key/id))))

(defn- extract-api-keys
  [_config-conn main-conn _opts]
  (mapv sort-api-key-children
        (api-keys-entity/export-api-keys @main-conn)))

;; --- per-entity apply-fns --------------------------------------------------

(defn- apply-users!
  [config-conn _main-conn records {:keys [on-conflict]}]
  (users-entity/import-users! config-conn records on-conflict))

(defn- plainify-maps
  "Replace any flatland.ordered.map values in the structure with plain Clojure
   maps. agents/upsert-agent! pr-strs :guardrails for storage; ordered-maps
   round-trip through pr-str as `#ordered/map` which EDN can't read back."
  [x]
  (walk/postwalk
   (fn [v]
     (if (instance? flatland.ordered.map.OrderedMap v)
       (into {} v)
       v))
   x))

(defn- apply-agents!
  [config-conn _main-conn records _opts]
  ;; Reverse the qualify-keys-with done at extract time.
  (agents-entity/import-agents! config-conn
                                (mapv #(-> %
                                           (unqualify-keys-from "agent")
                                           plainify-maps)
                                      records)))

(defn- apply-folders!
  [_config-conn main-conn records {:keys [on-conflict]}]
  (folders-entity/import-folders! main-conn records on-conflict))

(defn- apply-api-keys!
  [config-conn main-conn records {:keys [on-conflict]}]
  (api-keys-entity/import-api-keys! config-conn main-conn records on-conflict))

;; --- conversations (JSONL, partitioned by created date) --------------------

(def ^:private conversations-folder "conversations")
(def ^:private undated-conversations-file "undated.jsonl")

(def ^:private utc-date-formatter
  (-> (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd")
      (.withZone java.time.ZoneOffset/UTC)))

(defn- created-date-string
  "Convert :conversation/created (epoch ms) to a UTC YYYY-MM-DD string,
   or nil if missing/unparseable."
  [created]
  (when (number? created)
    (.format utc-date-formatter (java.time.Instant/ofEpochMilli (long created)))))

(defn- extract-conversations
  [_config-conn main-conn _opts]
  (conversations-entity/export-conversations @main-conn))

(defn- write-conversations-files!
  [dump-dir _spec records _opts]
  (let [by-date (->> records
                     (group-by #(or (created-date-string (:conversation/created %))
                                    "undated"))
                     (sort-by first))]
    (mapv
      (fn [[date-str date-records]]
        (let [filename (str conversations-folder "/"
                            (if (= "undated" date-str)
                              undated-conversations-file
                              (str date-str ".jsonl")))
              out-file (io/file dump-dir filename)
              ;; Sort within a day by created+id for deterministic output.
              sorted (->> date-records
                          (sort-by (juxt :conversation/created :conversation/id))
                          vec)]
          (io/make-parents out-file)
          (jsonl-fmt/write-records out-file sorted)
          {:filename filename :count (count sorted)}))
      by-date)))

(defn- read-conversations-files
  [dump-dir _spec _opts]
  (let [folder (io/file dump-dir conversations-folder)]
    (if-not (.exists folder)
      []
      (->> (.listFiles folder)
           (filter #(str/ends-with? (.getName ^File %) ".jsonl"))
           sort
           (mapcat jsonl-fmt/read-records)
           vec))))

(defn- apply-conversations!
  [_config-conn main-conn records {:keys [on-conflict]}]
  (conversations-entity/import-conversations! main-conn records on-conflict))

;; --- Spec table ------------------------------------------------------------

(def entity-specs
  "Listed in import order; export order matches.
   :envelope-key is the slot under {:data ...} that this entity's records
   feed into when handed back to config-sync/import-data."
  [{:key :config-defs
    :filename "00-config-defs.yaml"
    :namespace "config-def"
    :collection-key :defs
    :natural-key :config-def/path
    :secret-keys #{}
    :envelope-key :definitions
    :extract-fn extract-config-defs}
   {:key :config-nodes
    :filename "01-config-nodes.yaml"
    :namespace "config.node"
    :collection-key :nodes
    :natural-key :config.node/id
    :secret-keys #{}
    :envelope-key :nodes
    :extract-fn extract-config-nodes}
   {:key :datasets
    :filename "02-config-datasets.yaml"
    :namespace "dataset"
    :collection-key :datasets
    :natural-key :dataset/id
    :secret-keys #{}
    :envelope-key :datasets
    :extract-fn extract-datasets}
   {:key :dataset-pipelines
    :filename "03-config-dataset-pipelines.yaml"
    :namespace "dataset.pipeline"
    :collection-key :pipelines
    :natural-key :dataset.pipeline/id
    :secret-keys #{}
    :envelope-key :dataset-pipelines
    :extract-fn extract-dataset-pipelines}
   {:key :config-values
    :folder values-folder
    :envelope-key :node-values
    :extract-fn extract-config-values
    :write-fn write-config-values-files!
    :read-fn read-config-values-files}
   {:key :users
    :filename "05-users.yaml"
    :namespace "user"
    :collection-key :users
    :natural-key :user/email
    :extract-fn extract-users
    :apply-fn apply-users!}
   {:key :agents
    :filename "06-agents.yaml"
    :namespace "agent"
    :collection-key :agents
    :natural-key :agent/id
    :extract-fn extract-agents
    :apply-fn apply-agents!}
   {:key :folders
    :filename "07-folders.yaml"
    :namespace "folder"
    :collection-key :folders
    :natural-key :folder/id
    :extract-fn extract-folders
    :apply-fn apply-folders!}
   {:key :api-keys
    :filename "08-api-keys.yaml"
    :namespace "api-key"
    :collection-key :keys
    :natural-key :api-key/id
    ;; Current exports contain only a one-way digest and non-secret display
    ;; metadata. :api-key/key remains accepted by the import normalizer solely
    ;; for old encrypted archives and is never emitted by current exports.
    :extract-fn extract-api-keys
    :apply-fn apply-api-keys!}
   {:key :conversations
    :folder conversations-folder
    :extract-fn extract-conversations
    :write-fn write-conversations-files!
    :read-fn read-conversations-files
    :apply-fn apply-conversations!}])

;; --- Export ----------------------------------------------------------------

(defn- write-entity!
  [dump-dir spec records master-key]
  (let [{:keys [filename namespace collection-key natural-key secret-keys]} spec
        out-file (io/file dump-dir filename)
        prepared (mapv sort-fields records)
        write-opts (cond-> {:namespace namespace
                            :collection-key collection-key
                            :key-fn natural-key
                            :drop-keys #{natural-key}}
                     (seq secret-keys) (assoc :secret-keys secret-keys
                                              :master-key master-key))
        yaml-str (yaml-fmt/write prepared write-opts)]
    (io/make-parents out-file)
    (spit out-file yaml-str)
    {:filename filename :count (count records)}))

(defn export-system
  "Export system state to a dump folder.

   Args:
     config-conn  config DB connection
     main-conn    main DB connection (unused for current entities)
     dump-dir     destination folder. If nil, defaults to
                  dumps/<UTC-timestamp>/ relative to *cwd*.
     opts         {:master-key string} — passed to extractors that need it."
  [config-conn main-conn dump-dir opts]
  (let [dir (io/file (or dump-dir (str "dumps/" (timestamp-folder-name))))
        ;; export-full is heavyweight; cache for the duration of one dump.
        ;; Don't pass :export-password — encrypted values stay as live-DB
        ;; ciphertext (tied to CONFIG_MASTER_KEY).
        canonical (delay (-> (config-sync/export-full config-conn
                                                      {:include-audit? false})
                             :data))
        opts* (assoc opts :canonical canonical)
        default-write (fn [d spec records o]
                        [(write-entity! d spec records (:master-key o))])
        files (into []
                    (mapcat (fn [spec]
                              (let [records ((:extract-fn spec) config-conn main-conn opts*)
                                    write-fn (or (:write-fn spec) default-write)]
                                (write-fn dir spec records opts*))))
                    entity-specs)
        manifest {:version dump-version
                  :exported-at (.toString (Instant/now))
                  :encrypted-with "env CONFIG_MASTER_KEY"
                  :files files}]
    (spit (io/file dir "manifest.yaml")
          (yaml/generate-string manifest :dumper-options {:flow-style :block}))
    {:dump-dir dir :files files}))

;; --- Import ----------------------------------------------------------------

(defn- read-entity
  [dump-dir spec opts]
  (let [{:keys [filename natural-key]} spec
        in-file (io/file dump-dir filename)]
    (yaml-fmt/read (slurp in-file) (assoc opts :reinject-key natural-key))))

(defn import-system
  "Import system state from a dump folder.

   Args:
     config-conn  config DB connection
     main-conn    main DB connection (unused for current entities)
     dump-dir     source folder
     opts         {:master-key string :on-conflict :skip|:overwrite}"
  [config-conn main-conn dump-dir {:keys [master-key on-conflict]
                                   :or {on-conflict :skip}}]
  (let [dir (io/file dump-dir)
        default-read (fn [d spec opts] (:records (read-entity d spec opts)))
        ;; Read every entity's records up front; we'll dispatch by spec shape.
        records-by-key
        (reduce (fn [acc spec]
                  (let [read-fn (or (:read-fn spec) default-read)]
                    (assoc acc (:key spec) (read-fn dir spec {:master-key master-key}))))
                {}
                entity-specs)
        ;; Config-related entities batch into one config-sync/import-data call.
        config-envelope-data
        (reduce (fn [acc spec]
                  (if-let [k (:envelope-key spec)]
                    (assoc acc k (records-by-key (:key spec)))
                    acc))
                {}
                entity-specs)
        config-envelope {:version dump-version
                         :scope "system"
                         :data config-envelope-data}]
    (config-db/init-config-db! config-conn)
    (config-sync/import-data config-conn config-envelope
                             {:master-key master-key
                              :on-conflict on-conflict})
    ;; Per-entity apply-fns (users/agents/folders/api-keys/...) run after the
    ;; config phase, in spec order.
    (doseq [spec entity-specs
            :when (:apply-fn spec)]
      ((:apply-fn spec) config-conn main-conn (records-by-key (:key spec))
       {:master-key master-key :on-conflict on-conflict}))
    {:imported (->> entity-specs
                    (map (fn [spec] [(:key spec) (count (records-by-key (:key spec)))]))
                    (into {}))}))
