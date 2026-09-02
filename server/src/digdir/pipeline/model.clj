(ns digdir.pipeline.model
  "Pure pipeline identity and source-configuration logic."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(defn make-pipeline-id
  "Generate a pipeline ID from tenant, tenant-config-key, and pipeline name.

   Format: tenant:tenant-config-key:pipeline-name
   Examples:
   - 'ka:default:main-pipeline'
   - 'altinn:prod:website-pipeline'
   - '_:_:default-pipeline' (global default)"
  [tenant tenant-config-key pipeline-name]
  (str (or tenant "_") ":" (or tenant-config-key "_") ":" pipeline-name))

(defn parse-pipeline-id
  "Parse a pipeline ID into its components.

   Returns: {:tenant string-or-nil, :tenant-config-key string-or-nil, :pipeline-name string}"
  [pipeline-id]
  (let [[tenant tenant-config-key pipeline-name] (str/split pipeline-id #":" 3)]
    {:tenant (when (not= tenant "_") tenant)
     :tenant-config-key (when (not= tenant-config-key "_") tenant-config-key)
     :pipeline-name pipeline-name}))

(def source-specific-properties
  "Allowed source-specific properties keyed by source type."
  {:kudos #{:kudos-use-preprod
            :kudos-starting-page
            :kudos-document-types
            :kudos-transducer}
   :website #{:website-sitemap-url
              :website-base-url}
   :folder #{:folder-path}
   :episerver #{:episerver-xml-path
                :episerver-language
                :episerver-include-page-types}})

(def source-required-properties
  "Required source-specific properties keyed by source type."
  {:kudos #{}
   :website #{:website-sitemap-url}
   :folder #{:folder-path}
   :episerver #{:episerver-xml-path}})

(def source-shared-properties
  "Shared properties used for source ingestion limits."
  #{:document-limit :document-offset})

(def all-source-types
  "All supported source types."
  (set (keys source-specific-properties)))

(def all-source-specific-properties
  "Union of all source-specific properties."
  (apply set/union #{} (vals source-specific-properties)))

(defn infer-source-type
  "Infer source type from present source-specific properties.

   Returns the unique matching source type when inference is unambiguous,
   otherwise nil."
  [properties]
  (let [present-props (set (keys properties))
        matching-types (keep (fn [[source-type allowed-props]]
                               (when (seq (set/intersection present-props allowed-props))
                                 source-type))
                             source-specific-properties)]
    (when (= 1 (count matching-types))
      (first matching-types))))

(defn validate-source-config!
  "Validate source-specific pipeline properties.
   - On create, source type is required and required source fields must be present.
   - On update, source type is optional unless source-specific fields are being changed."
  [properties {:keys [require-source-type?] :or {require-source-type? false}}]
  (let [source-type (:source-type properties)
        present-props (set (keys properties))
        present-source-specific (set/intersection present-props all-source-specific-properties)]
    (when (and (nil? source-type) (seq present-source-specific))
      (throw (ex-info "source-type is required when setting source-specific properties"
                      {:status 400
                       :source-properties (sort present-source-specific)})))
    (when (and require-source-type? (nil? source-type))
      (throw (ex-info "Missing required property: source-type" {:status 400})))
    (when source-type
      (when-not (contains? all-source-types source-type)
        (throw (ex-info "Invalid source-type"
                        {:status 400
                         :source-type source-type
                         :allowed-source-types (sort all-source-types)})))
      (let [allowed (set/union source-shared-properties
                               (get source-specific-properties source-type))
            required (get source-required-properties source-type #{})
            invalid-source-props (set/difference present-source-specific
                                                 (get source-specific-properties source-type #{}))
            missing-required (set/difference required present-props)]
        (when (seq invalid-source-props)
          (throw (ex-info "Source-specific properties do not match source-type"
                          {:status 400
                           :source-type source-type
                           :invalid-properties (sort invalid-source-props)
                           :allowed-source-properties (sort allowed)})))
        (when (seq missing-required)
          (throw (ex-info "Missing required source properties"
                          {:status 400
                           :source-type source-type
                           :missing-properties (sort missing-required)})))))))
