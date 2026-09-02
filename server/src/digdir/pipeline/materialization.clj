(ns digdir.pipeline.materialization
  "Canonical materialization contract for Dataset-root pipeline config.

   This namespace now defines only the explicit deployment-target materialization
   contract plus the loader-mapping boundary. Hard-coded fallback values have
   been retired from the execution path."
  (:require [digdir.docs.pipeline.search-phrases :as search-phrases]))

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

(def ^:private source-loader-key-map
  {:kudos {:kudos-use-preprod :kudos/use-preprod?
           :kudos-starting-page :kudos/starting-page
           :kudos-document-types :documents/types
           :document-limit :documents/limit
           :document-offset :documents/offset
           :kudos-transducer :documents/transducer}
   :website {:website-sitemap-url :sitemap/url
             :website-base-url :base-url
             :document-limit :urls/limit
             :document-offset :urls/offset}
   :folder {:folder-path :folder/path
            :document-limit :files/limit
            :document-offset :files/offset}
   :episerver {:episerver-xml-path :xml/path
               :episerver-language :language
               :episerver-include-page-types :pages/include-page-types
               :document-limit :pages/limit
               :document-offset :pages/offset}})

(def ^:private shared-loader-key-map
  "Shared properties every pipeline MUST set, mapped to their loader keys.

   THIS MAP IS A CONTRACT, NOT A TRANSLATION TABLE. `required-execution-properties`
   derives the required set from `(keys shared-loader-key-map)`, so adding a key
   here makes it MANDATORY for every dataset: materialization then throws
   \"Pipeline materialization config incomplete\" for every existing pipeline that
   does not set it.

   That is invisible in a diff — a new entry here looks exactly like its
   siblings, and the meaning lives in a function three definitions away. It is
   how #453 first shipped a red build. If the key should be optional, put it in
   `optional-shared-loader-key-map` instead."
  {:chunk-strategy :chunks/strategy
   :chunk-minimum-length :chunks/minimum-length
   :chunk-maximum-length :chunks/maximum-length
   :search-phrases-model :search-phrases/model
   :search-phrases-fallback :search-phrases/fallback-model
   :search-phrases-prompt :search-phrases/prompt
   :collection-prefix :store/coll-prefix
   :parallelism-documents :parallelism/documents
   :parallelism-store :parallelism/store
   :max-document-failures :fault-tolerance/max-document-failures})

(def ^:private optional-shared-loader-key-map
  "Shared properties translated when present and simply ABSENT otherwise.

   Deliberately not part of the required contract above: a key here never makes
   an existing dataset fail materialization, and when it is unset the loader
   config does not carry it at all rather than carrying nil. That keeps
   \"absent => no-op\" true at the consumption site (see `split-oversized-chunks`
   in `digdir.rag.chunking`) rather than depending on every consumer treating nil
   the way the default would.

   `materialization-test` guards the distinction: any key in this map that turns
   up in `required-execution-properties` fails the build."
  {:chunk-split-max-length :chunks/split-max-length})

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
        source-keys (get source-loader-key-map source-type {})]
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
     ;; Optional keys are omitted entirely when unset, so a pipeline that does
     ;; not use one produces exactly the loader config it did before the key
     ;; existed.
     (into {}
           (keep (fn [[source-key loader-key]]
                   (when (contains? pipeline-config source-key)
                     [loader-key (get pipeline-config source-key)])))
           optional-shared-loader-key-map))))
