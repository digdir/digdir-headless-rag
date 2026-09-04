(ns digdir.pipeline.materialization
  "Canonical materialization contract for Dataset-root pipeline config.

   This namespace now defines only the explicit deployment-target materialization
   contract plus the loader-mapping boundary. Hard-coded fallback values have
   been retired from the execution path."
  (:require [digdir.docs.pipeline.search-phrases :as search-phrases]
            [digdir.pipeline.model :as model]))

(def ^:private deployment-target-materialization-contracts
  {["digdir" "public-docs"]
   {:document-limit 30000
    :document-offset 0
    :chunk-strategy :header-based
    :chunk-minimum-length 333
    :chunk-maximum-length (* 2 128000)
    :search-phrases-model "gpt-4o"
    :search-phrases-fallback :google/gemma-3-27b-it
    :search-phrases-prompt search-phrases/default-search-phrases-prompt
    :collection-prefix "website_"
    :parallelism-documents 3
    :parallelism-store 1
    :max-document-failures 10}

   ["public-sector-knowledge" "kudos"]
   {:kudos-use-preprod false
    :kudos-starting-page 1
    :document-limit 20000
    :document-offset 0
    :chunk-strategy :header-based
    :chunk-minimum-length 333
    :chunk-maximum-length (* 2 128000)
    :search-phrases-model "gpt-4o"
    :search-phrases-fallback :google/gemma-3-27b-it
    :search-phrases-prompt search-phrases/default-search-phrases-prompt
    :collection-prefix "KUDOS_preprod_v4_"
    :parallelism-documents 3
    :parallelism-store 1
    :max-document-failures 10}})

(def ^:private source-types
  "The source types the loader maps are built for."
  [:kudos :website :folder :episerver])

;; ⚠️ THE FOUR MAPS BELOW ARE NOW DERIVED, NOT DECLARED (#513).
;;
;; They used to be four literal maps, and MEMBERSHIP OF A MAP WAS THE ONLY
;; STATEMENT OF REQUIREDNESS: `required-execution-properties` took
;; `(keys source-loader-key-map)`, so that map did two jobs — translate a
;; property to a loader key, AND declare it mandatory — while the second job
;; was never written down anywhere. `model.clj` then stated requiredness a
;; second time, independently and smaller, and the two drifted. That drift IS
;; #513: `:website-base-url` optional in the model, required here, so the API
;; accepted a website dataset that materialization then refused to execute.
;;
;; Both layers now derive from `model/dataset-properties`, which carries the
;; three facts per property — loader key, required-or-optional, and which
;; source types it applies to. Adding a property is one line there and cannot
;; make the two layers disagree.
;;
;; The four shapes are KEPT rather than collapsed into one, because
;; `dataset-config->loader-config` treats required and optional differently on
;; purpose: a required key is read unconditionally, an optional one is omitted
;; entirely when unset so "absent => no-op" stays true at the consumption site.

(defn- loader-key-map
  "Property -> loader key for `source-type`, restricted to declarations whose
   requiredness is `required?` and whose scope is per-source (`scope` = `:source`)
   or pipeline-wide (`:all`)."
  [source-type required? scope]
  (into {}
        (->> (model/properties-for source-type required?)
             (filter (fn [{:keys [source-types]}]
                       (if (= :source scope) (set? source-types) (= :all source-types))))
             (map (fn [p] [(:property p) (model/loader-key-for p source-type)])))))

(def ^:private source-loader-key-map
  "Source-specific properties a pipeline of that source type MUST set, mapped to
   their loader keys. DERIVED from `model/dataset-properties`."
  (into {} (map (fn [st] [st (loader-key-map st true :source)])) source-types))

(def ^:private optional-source-loader-key-map
  "Source-specific properties translated when present and simply ABSENT
   otherwise. DERIVED from `model/dataset-properties`.

   `{:folder {:folder-base-url :base-url}}` is the worked example of why the
   declaration needs per-source applicability: the same loader key `:base-url`
   is required for `:website` and optional for `:folder` (#506/#504)."
  (into {} (map (fn [st] [st (loader-key-map st false :source)])) source-types))

(def ^:private shared-loader-key-map
  "Pipeline-wide properties every pipeline MUST set. DERIVED."
  (loader-key-map :kudos true :all))

(def ^:private optional-shared-loader-key-map
  "Pipeline-wide properties translated when present, absent otherwise. DERIVED."
  (loader-key-map :kudos false :all))

(def ^:private derived-loader-values
  {:chunks/hash-changer 1
   :search-phrases/hash-changer 1})

(defn target-materialization-contract
  "Return the explicit deployment-target materialization contract for a
   tenant/dataset pair, or nil when the pipeline is not one of the active
   deployment targets."
  [pipeline-config]
  (get deployment-target-materialization-contracts
       [(:tenant pipeline-config) (:dataset-id pipeline-config)]))

(defn missing-target-contract-properties
  "Return only the explicit deployment-target contract properties that are still absent."
  [pipeline-config]
  (let [required-properties (target-materialization-contract pipeline-config)]
    (reduce-kv (fn [acc k v]
                 (if (contains? pipeline-config k)
                   acc
                   (assoc acc k v)))
               {}
               required-properties)))

(defn drifted-target-contract-properties
  "Return explicit deployment-target contract properties whose effective values
   differ from the canonical target contract."
  [pipeline-config]
  (let [required-properties (target-materialization-contract pipeline-config)]
    (reduce-kv (fn [acc k v]
                 (if (= v (get pipeline-config k ::missing))
                   acc
                   (assoc acc k v)))
               {}
               required-properties)))

(defn required-execution-properties
  "Return the properties that execution explicitly requires for the given pipeline config."
  [pipeline-config]
  (let [source-type (:source-type pipeline-config)
        source-keys (keys (get source-loader-key-map source-type {}))
        shared-keys (keys shared-loader-key-map)]
    (into {}
          (map (fn [k] [k true]))
          (concat source-keys shared-keys))))

(defn require-explicit-materialization-config!
  [pipeline-config]
  (let [required-properties (required-execution-properties pipeline-config)
        missing-properties (->> required-properties
                                keys
                                (remove #(contains? pipeline-config %))
                                sort
                                vec)]
    (when (seq missing-properties)
      (throw (ex-info "Pipeline materialization config incomplete"
                      {:tenant (:tenant pipeline-config)
                       :dataset-id (:dataset-id pipeline-config)
                       :pipeline-id (:pipeline-id pipeline-config)
                       :pipeline-name (:pipeline-name pipeline-config)
                       :source-type (:source-type pipeline-config)
                       :missing-properties missing-properties})))))

(defn dataset-config->loader-config
  "Convert resolved Dataset-root pipeline config into the canonical loader shape."
  [pipeline-config]
  (require-explicit-materialization-config! pipeline-config)
  (let [source-type (:source-type pipeline-config)
        source-keys (get source-loader-key-map source-type {})
        ;; Optional keys are omitted entirely when unset, so a pipeline that
        ;; does not use one produces exactly the loader config it did before the
        ;; key existed.
        ;;
        ;; ONE implementation, used for both optional maps. Writing the `keep`
        ;; out twice would be two definitions of what "optional" means, free to
        ;; drift into disagreeing — which is the shape of #497 and #500.
        translate-optional (fn [key-map]
                             (into {}
                                   (keep (fn [[source-key loader-key]]
                                           (when (contains? pipeline-config source-key)
                                             [loader-key (get pipeline-config source-key)])))
                                   key-map))]
    (merge
     {:tenant (:tenant pipeline-config)}
     derived-loader-values
     (into {}
           (map (fn [[source-key loader-key]]
                  [loader-key (get pipeline-config source-key)]))
           source-keys)
     (into {}
           (map (fn [[source-key loader-key]]
                  [loader-key (get pipeline-config source-key)]))
           shared-loader-key-map)
     (translate-optional optional-shared-loader-key-map)
     (translate-optional (get optional-source-loader-key-map source-type {})))))
