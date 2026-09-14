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

(def dataset-properties
  "THE declaration. One entry per dataset property, and the only place any of
   these three facts is stated.

   ## Why this exists (#513)

   Requiredness used to be stated TWICE, in two layers, in two shapes:

     - `model.clj` listed required properties directly, and
     - `materialization.clj` derived its required set from
       `(keys source-loader-key-map)` — a map whose actual job is translating a
       property name to a loader key.

   So in materialization, MAPPABLE WAS CONFLATED WITH REQUIRED: one map doing
   two jobs, the second never declared anywhere. The two layers then drifted,
   which is #513 exactly — `:website-base-url` was optional in the model and
   required in materialization, so the API accepted a website dataset that
   materialization could not execute.

   ⚠️ THE FIX IS NOT 'MAKE THE TWO LISTS MATCH'. Two lists that agree today are
   two lists that can disagree tomorrow, and nothing would notice. Both layers
   now DERIVE from this, so the question 'is this required?' has exactly one
   answer by construction.

   ## The three facts, and why the third is not optional

   `:loader-key` — a keyword, or a map from source-type to keyword when the same
   property translates differently per source (`:document-limit` is
   `:documents/limit` for kudos but `:urls/limit` for website).

   `:required?` — the fact that had no home. This is the one #513 is about.

   `:source-types` — a set, or `:all` for pipeline-wide properties.

   The third fact is load-bearing rather than bookkeeping: `:base-url` is ONE
   loader key reached by two properties, required for `:website` and optional
   for `:folder` (#506/#504 — a website is reached over HTTP so an address always
   exists, while a folder corpus is local markdown that may have no public
   address at all). Without per-source applicability that distinction cannot be
   expressed, and promoting `:base-url` to a shared property would offer it to
   `:kudos` and `:episerver` as well, which is not established to be right."
  [;; ---- source-specific -----------------------------------------------------
   {:property :kudos-use-preprod    :loader-key :kudos/use-preprod?   :required? true  :source-types #{:kudos}}
   {:property :kudos-starting-page  :loader-key :kudos/starting-page  :required? true  :source-types #{:kudos}}
   {:property :kudos-document-types :loader-key :documents/types      :required? true  :source-types #{:kudos}}
   {:property :kudos-transducer     :loader-key :documents/transducer :required? true  :source-types #{:kudos}}

   {:property :website-sitemap-url  :loader-key :sitemap/url          :required? true  :source-types #{:website}}
   ;; #513: required here AND in materialization, from this one line. It was the
   ;; disagreement between the two that let a website dataset be accepted and
   ;; then refused at execution.
   {:property :website-base-url     :loader-key :base-url             :required? true  :source-types #{:website}}

   {:property :folder-path          :loader-key :folder/path          :required? true  :source-types #{:folder}}
   ;; Optional deliberately, and the same loader key as :website-base-url — see
   ;; the docstring above.
   {:property :folder-base-url      :loader-key :base-url             :required? false :source-types #{:folder}}

   {:property :episerver-xml-path           :loader-key :xml/path                 :required? true :source-types #{:episerver}}
   {:property :episerver-language           :loader-key :language                 :required? true :source-types #{:episerver}}
   {:property :episerver-include-page-types :loader-key :pages/include-page-types :required? true :source-types #{:episerver}}

   ;; Same property, different loader key per source — which is why `:loader-key`
   ;; accepts a per-source-type map rather than only a keyword.
   {:property :document-limit  :required? true :source-types #{:kudos :website :folder :episerver}
    :loader-key {:kudos :documents/limit :website :urls/limit :folder :files/limit :episerver :pages/limit}}
   {:property :document-offset :required? true :source-types #{:kudos :website :folder :episerver}
    :loader-key {:kudos :documents/offset :website :urls/offset :folder :files/offset :episerver :pages/offset}}

   ;; ---- pipeline-wide -------------------------------------------------------
   {:property :chunk-strategy        :loader-key :chunks/strategy        :required? true :source-types :all}
   {:property :chunk-minimum-length  :loader-key :chunks/minimum-length  :required? true :source-types :all}
   {:property :chunk-maximum-length  :loader-key :chunks/maximum-length  :required? true :source-types :all}
   {:property :search-phrases-model    :loader-key :search-phrases/model          :required? true :source-types :all}
   {:property :search-phrases-fallback :loader-key :search-phrases/fallback-model :required? true :source-types :all}
   {:property :search-phrases-prompt   :loader-key :search-phrases/prompt         :required? true :source-types :all}
   {:property :collection-prefix     :loader-key :store/coll-prefix      :required? true :source-types :all}
   {:property :parallelism-documents :loader-key :parallelism/documents  :required? true :source-types :all}
   {:property :parallelism-store     :loader-key :parallelism/store      :required? true :source-types :all}
   {:property :max-document-failures :loader-key :fault-tolerance/max-document-failures :required? true :source-types :all}
   ;; Optional: absent means the loader config does not carry the key at all,
   ;; rather than carrying nil, so "absent => no-op" stays true at the
   ;; consumption site.
   {:property :chunk-split-max-length :loader-key :chunks/split-max-length :required? false :source-types :all}])

(defn properties-for
  "Declared properties applying to `source-type`, optionally filtered by
   requiredness. `:all` entries apply to every source type."
  ([source-type] (properties-for source-type nil))
  ([source-type required?]
   (->> dataset-properties
        (filter (fn [{:keys [source-types]}]
                  (or (= :all source-types) (contains? source-types source-type))))
        (filter (fn [{r :required?}] (or (nil? required?) (= required? r)))))))

(defn loader-key-for
  "The loader key a property translates to for `source-type`."
  [{:keys [loader-key]} source-type]
  (if (map? loader-key) (get loader-key source-type) loader-key))

(def source-specific-properties
  "Allowed source-specific properties keyed by source type. DERIVED — see
   `dataset-properties`.

   Source-specific means `:source-types` is a set: `:all` entries are
   pipeline-wide and are not part of this per-source allow-list."
  (into {}
        (map (fn [st]
               [st (->> dataset-properties
                        (filter (fn [{:keys [source-types]}]
                                  (and (set? source-types) (contains? source-types st))))
                        (map :property)
                        ;; `:document-limit`/`:document-offset` are declared per
                        ;; source type for their loader keys, but the model has
                        ;; always treated them as shared (see
                        ;; `source-shared-properties`), so they stay out of the
                        ;; per-source allow-list to keep that boundary unchanged.
                        (remove #{:document-limit :document-offset})
                        set)]))
        [:kudos :website :folder :episerver]))

(def source-required-properties
  "Required source-specific properties keyed by source type. DERIVED — see
   `dataset-properties`."
  (into {}
        (map (fn [st]
               [st (->> (properties-for st true)
                        (filter (comp set? :source-types))
                        (map :property)
                        (remove #{:document-limit :document-offset})
                        set)]))
        [:kudos :website :folder :episerver]))

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
        ;; ⚠️ COMPLETENESS IS ENFORCED ON CREATE ONLY, and this became visible
        ;; rather than new with #513.
        ;;
        ;; `missing-required` is computed against the PROPERTIES SUPPLIED IN THIS
        ;; CALL, which on update is a PATCH rather than the whole dataset. So
        ;; enforcing it on update would mean every partial update had to resend
        ;; every required property — renaming a kudos dataset would fail unless
        ;; you also resent all four kudos properties.
        ;;
        ;; That was latent before: the API required nothing for `:kudos` and one
        ;; property for the others, so a patch almost never tripped it. Aligning
        ;; the API with what execution requires makes it reachable, which is why
        ;; the create/update split — already stated in this function's docstring
        ;; — has to be made real here.
        ;;
        ;; WHAT THIS DOES NOT COVER, stated rather than left to be found: an
        ;; update cannot introduce a missing required property, but it also
        ;; cannot be used to CHECK one. A dataset created before this change
        ;; without its required properties stays invalid-but-stored until
        ;; something re-creates it. Validating the MERGED result on update is the
        ;; complete answer and needs the stored properties at this call site,
        ;; which this function does not have.
        (when (and (seq missing-required) require-source-type?)
          (throw (ex-info "Missing required source properties"
                          {:status 400
                           :source-type source-type
                           :missing-properties (sort missing-required)})))))))
