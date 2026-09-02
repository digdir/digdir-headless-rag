(ns digdir.skills.enrichment.naming
  "Pure naming helpers for the parallel enrichment collections.

   Promoted to `src/` (production) from the offline tooling namespace
   `digdir.skills.enrichment.collections` (src-dev) so the runtime retrieval
   skill — and any other production caller — can derive enrichment collection
   names without depending on dev-only code. This namespace stays free of
   Typesense and DB access: it does string/keyword transforms, plus the pure
   config-hash used to build a name from a pipeline-config.

   The enrichment collection name mirrors the base docs/chunks/phrases trio:
   `{prefix}enrichment_{type}_{hash}`. The `-from-base` helpers derive it from
   an already-resolved sibling base collection name (which shares the same
   `{prefix}_{hash}`), so callers that thread collection-name strings rather than
   a pipeline-config can resolve enrichment collections without a DB round-trip.

   Callers that hold a pipeline-config use `enrichment-collection-name` /
   `enrichment-collection-names` below. Those lived in the src-dev namespace
   `digdir.skills.enrichment.collections` until issue #30: production code in
   `src/` needs them too (the Typesense debug endpoints resolve enrichment
   collections from a dataset-config), and requiring src-dev from src breaks
   the production artifact, which has no src-dev on its classpath. They live
   here now and the src-dev namespace re-exports them for offline tooling."
  (:require [clojure.string :as str]
            [digdir.pipeline.collections :as pipeline-coll]))

(def enrichment-types
  "Canonical set of enrichment-type keywords. Each maps to a Typesense
   collection name segment via `type->name-segment`."
  #{:hypothetical-questions
    :verified-phrases
    :fact-assertions
    :knowledge-graph})

(defn type->name-segment
  "Stable underscore-style segment used inside the collection name.
   Keep this in sync with `enrichment-types`; an unknown type is a bug."
  [enrichment-type]
  (case enrichment-type
    :hypothetical-questions "hypothetical_questions"
    :verified-phrases       "verified_phrases"
    :fact-assertions        "fact_assertions"
    :knowledge-graph        "knowledge_graph"
    (throw (ex-info "Unknown enrichment type"
                    {:enrichment-type enrichment-type
                     :known enrichment-types}))))

(defn enrichment-collection-name-from-base
  "Derive the enrichment collection name from an already-resolved SIBLING
   base collection name (docs/chunks/phrases), which shares the same
   `{prefix}_{hash}`. Lets callers that only hold the resolved base name
   (e.g. the agent runtime, which threads collection-name strings rather than
   the pipeline-config) resolve enrichment collections without a DB round-trip."
  [base-collection enrichment-type]
  (str/replace base-collection
               #"(?:documents|chunks|phrases)_"
               (str "enrichment_" (type->name-segment enrichment-type) "_")))

(defn enrichment-collection-names-from-base
  "Map `{enrichment-type collection-name}` derived from a sibling base
   collection name. See `enrichment-collection-name-from-base`."
  [base-collection enrichment-types-seq]
  (into {}
        (map (fn [t] [t (enrichment-collection-name-from-base base-collection t)]))
        enrichment-types-seq))

(defn enrichment-collection-name
  "Build the Typesense collection name for `enrichment-type` paired to
   the given `pipeline-config`. Uses the same prefix and hash as the
   base docs/chunks/phrases trio so the enrichment is unambiguously
   bound to the chunks it enriches.

   Prefer this over `enrichment-collection-name-from-base` when you hold the
   config: the `-from-base` variant rewrites an existing collection name by
   regex, so a base name that does not follow the `{prefix}{type}_{hash}`
   convention is returned UNCHANGED rather than rejected.

   Examples:
     enrichment_hypothetical_questions_<hash>
     prod_main_enrichment_hypothetical_questions_<hash>"
  [pipeline-config enrichment-type]
  (let [prefix   (or (:collection-prefix pipeline-config)
                     (when-let [pn (:pipeline-name pipeline-config)]
                       (str (str/replace pn #"[^a-zA-Z0-9_]" "_") "_"))
                     "pipeline_")
        hash-val (pipeline-coll/pipeline-config-hash pipeline-config)
        segment  (type->name-segment enrichment-type)]
    (str prefix "enrichment_" segment "_" hash-val)))

(defn enrichment-collection-names
  "Generate enrichment collection names for `enrichment-types-seq` given
   a base `pipeline-config`. Returns a map `{enrichment-type collection-name}`.

   Convenience for callers that want one map to thread through downstream
   skill inputs without repeating the prefix/hash dance."
  [pipeline-config enrichment-types-seq]
  (into {}
        (map (fn [t] [t (enrichment-collection-name pipeline-config t)]))
        enrichment-types-seq))
