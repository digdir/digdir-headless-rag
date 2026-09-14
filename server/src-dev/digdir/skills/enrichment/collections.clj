(ns digdir.skills.enrichment.collections
  "Phase B — parallel enrichment collections (the additive storage layer).

   The self-improvement agent never touches running collections. Instead,
   each enrichment type lives in its own Typesense collection alongside
   the base docs/chunks/phrases collections, keyed by `chunk_id` and
   joinable to the base docs collection on `doc_num`.

   Naming mirrors `digdir.pipeline.collections/pipeline-collection-names`:
   `{prefix}enrichment_{type}_{hash}`, where the hash is reused from the
   base pipeline so an enrichment collection is unambiguously paired to
   the chunks it enriches. If the base pipeline rehashes (e.g. chunking
   strategy changes), the enrichment collection name changes too — old
   data is harmlessly orphaned, never silently joined to wrong chunks.

   This namespace lives in `src-dev/` because Phase B is offline tooling.
   It only defines schemas and naming/creation helpers; it does not
   modify the runtime retrieval skill. The runtime opts in via the
   `:enrichment-search-targets` parameter (added separately in B.4).

   Current enrichment types:
     :hypothetical-questions — 3-5 LLM-generated questions per chunk that
                               the chunk answers. Mirror of HyDE at index
                               time."
  (:require
            [digdir.pipeline.collections :as pipeline-coll]
            [digdir.skills.enrichment.naming :as naming]
            [digdir.docs.pipeline.storage :as storage]))

;; =============================================================================
;; Naming
;; =============================================================================
;; The pure naming helpers (type->name-segment, enrichment-types, and the
;; `-from-base` derivations) now live in the production ns
;; `digdir.skills.enrichment.naming` so the runtime retrieval skill can reuse
;; them. Re-exported here for back-compat with existing src-dev callers.

(def enrichment-types naming/enrichment-types)
(def type->name-segment naming/type->name-segment)
(def enrichment-collection-name-from-base naming/enrichment-collection-name-from-base)
(def enrichment-collection-names-from-base naming/enrichment-collection-names-from-base)

;; Config-based builders moved to `digdir.skills.enrichment.naming` (src/) in
;; issue #30 — production code needs them and src-dev is not on the prod
;; classpath. Re-exported here so offline tooling callers are unaffected.
(def enrichment-collection-name naming/enrichment-collection-name)
(def enrichment-collection-names naming/enrichment-collection-names)

;; All naming helpers now live in `digdir.skills.enrichment.naming` and are
;; re-exported above; this namespace keeps the schemas and Typesense creation.

;; =============================================================================
;; Schemas
;; =============================================================================

(defn hypothetical-questions-schema
  "Typesense schema for the `:hypothetical-questions` enrichment collection.

   Field shape mirrors `digdir.docs.website/website-phrases-schema`:
     - chunk_id     : the chunk this question targets (string, sortable)
     - doc_num      : reference into the base docs collection
     - question     : the generated question text
     - question_vec : MiniLM-L12-v2 embedding of `question` (auto-embed)

   Plus provenance fields for selective regeneration when prompts/models
   change:
     - model         : LLM that generated this question
     - prompt_hash   : sha256 of the prompt template used
     - generated_at  : epoch-ms

   Note: a chunk has 3-5 questions, so chunk_id is NOT unique. The
   `id` field Typesense auto-assigns is the row PK; consumers dedupe
   on (chunk_id, question) when needed.

   `coll-ids` is `[docs-collection-name questions-collection-name]` —
   the same two-element shape callers already build via
   `enrichment-collection-name` plus the base docs name."
  [[docs-collection-name questions-collection-name]]
  {:name questions-collection-name
   :default_sorting_field "chunk_id"
   :fields
   [{:facet true :index true :name "chunk_id" :optional false :sort true :type "string"}
    {:async_reference false :facet true :index true :name "doc_num" :optional false
     :reference (str docs-collection-name ".doc_num") :sort true :type "string"}
    {:facet false :index true :name "question" :optional false :sort false :type "string"}
    {:embed {:from ["question"] :model_config {:model_name "ts/all-MiniLM-L12-v2"}}
     :facet false :hnsw_params {:M 16 :ef_construction 200} :index true :name "question_vec"
     :num_dim 384 :optional true :sort false :type "float[]" :vec_dist "cosine"}
    {:facet false :index false :name "model" :optional true :sort false :type "string"}
    ;; `prompt_hash` must be `:index true` — the revert-chunk skill
    ;; filters by `prompt_hash:=[<hash>]` to scope a delete to one
    ;; propose call. Typesense silently returns zero matches when
    ;; filtering on an un-indexed field (no error), which manifested
    ;; as "revert returned 0 deletions" in Phase D1 traces. 2026-05-19.
    {:facet false :index true :name "prompt_hash" :optional true :sort false :type "string"}
    {:facet false :index false :name "generated_at" :optional true :sort true :type "int64"}]})

(defn verified-phrases-schema
  "Typesense schema for the `:verified-phrases` enrichment collection.

   Phase D1 (2026-05-19). Mirror of `hypothetical-questions-schema` —
   same field shape, same provenance fields, only the searchable text
   field is named `phrase` (with `phrase_vec` for embeddings) instead
   of `question`. The semantic role differs:

   - **Hypothetical questions** are search-style queries the chunk
     answers (`Når ble Altinn 3 lansert?`).
   - **Verified phrases** are short topical fragments that should
     match user queries through exact-substring + token overlap
     (`Altinn 3 launch date June 2020`, `Altinn 3 production July 2020`).

   Both feed the retrieval merge stage as parallel sibling strategies
   alongside phrase/metadata/content.

   `coll-ids` is `[docs-collection-name phrases-collection-name]`."
  [[docs-collection-name phrases-collection-name]]
  {:name phrases-collection-name
   :default_sorting_field "chunk_id"
   :fields
   [{:facet true :index true :name "chunk_id" :optional false :sort true :type "string"}
    {:async_reference false :facet true :index true :name "doc_num" :optional false
     :reference (str docs-collection-name ".doc_num") :sort true :type "string"}
    {:facet false :index true :name "phrase" :optional false :sort false :type "string"}
    {:embed {:from ["phrase"] :model_config {:model_name "ts/all-MiniLM-L12-v2"}}
     :facet false :hnsw_params {:M 16 :ef_construction 200} :index true :name "phrase_vec"
     :num_dim 384 :optional true :sort false :type "float[]" :vec_dist "cosine"}
    {:facet false :index false :name "model" :optional true :sort false :type "string"}
    ;; `:index true` — see comment in hypothetical-questions-schema.
    {:facet false :index true :name "prompt_hash" :optional true :sort false :type "string"}
    {:facet false :index false :name "generated_at" :optional true :sort true :type "int64"}]})

(defn fact-assertions-schema
  "Typesense schema for the `:fact-assertions` enrichment collection.

   Phase D2 (2026-05-20). Same mirror pattern as
   `verified-phrases-schema`, but the unit of work is a
   (subject, predicate, object) triple rather than a free-form phrase.
   Each row is one assertion the chunk makes, e.g.
   `{:subject \"Altinn 3\" :predicate \"ble lansert\" :object \"juni 2020\"}`.

   We keep subject/predicate/object as separate `:index true` strings
   so callers can filter or facet by any part (e.g. \"all assertions
   about Altinn 3\"), and we add a synthesized `triple_text` field
   that concatenates them (`\"<subject> <predicate> <object>\"`) for
   free-text retrieval. `triple_vec` auto-embeds `triple_text` for the
   semantic-similarity fallback path.

   Retrieval bias differs from verified-phrases:
   `lookup-fact-assertions-similar` uses `prioritize_exact_match=true`
   so entity-style queries like \"Altinn 3 lansert\" surface assertions
   that literally mention those tokens, instead of hand-waving toward
   the closest vector.

   `coll-ids` is `[docs-collection-name facts-collection-name]`."
  [[docs-collection-name facts-collection-name]]
  {:name facts-collection-name
   :default_sorting_field "chunk_id"
   :fields
   [{:facet true :index true :name "chunk_id" :optional false :sort true :type "string"}
    {:async_reference false :facet true :index true :name "doc_num" :optional false
     :reference (str docs-collection-name ".doc_num") :sort true :type "string"}
    {:facet true :index true :name "subject" :optional false :sort false :type "string"}
    {:facet true :index true :name "predicate" :optional false :sort false :type "string"}
    {:facet true :index true :name "object" :optional false :sort false :type "string"}
    {:facet false :index true :name "triple_text" :optional false :sort false :type "string"}
    {:embed {:from ["triple_text"] :model_config {:model_name "ts/all-MiniLM-L12-v2"}}
     :facet false :hnsw_params {:M 16 :ef_construction 200} :index true :name "triple_vec"
     :num_dim 384 :optional true :sort false :type "float[]" :vec_dist "cosine"}
    {:facet false :index false :name "model" :optional true :sort false :type "string"}
    ;; `:index true` — see comment in hypothetical-questions-schema.
    {:facet false :index true :name "prompt_hash" :optional true :sort false :type "string"}
    {:facet false :index false :name "generated_at" :optional true :sort true :type "int64"}]})

(defn schema-for
  "Dispatch to the right schema-builder fn for `enrichment-type`. Lets
   the apply skill stay generic across enrichment types as later phases
   add them."
  [enrichment-type coll-ids]
  (case enrichment-type
    :hypothetical-questions (hypothetical-questions-schema coll-ids)
    :verified-phrases       (verified-phrases-schema coll-ids)
    :fact-assertions        (fact-assertions-schema coll-ids)
    (throw (ex-info "No schema registered for enrichment type"
                    {:enrichment-type enrichment-type
                     :known #{:hypothetical-questions
                              :verified-phrases
                              :fact-assertions}}))))

;; =============================================================================
;; Creation
;; =============================================================================

(defn ensure-collection!
  "Create the Typesense collection for `enrichment-type` paired with
   `pipeline-config`'s base docs collection. Idempotent — returns
   `:already-exists` if the collection is already there.

   Args:
     pipeline-config — same shape `pipeline-collection-names` accepts.
                       Must supply enough keys to compute the base hash
                       and the docs-collection name (i.e. the result of
                       `pipeline-collection-names`).
     enrichment-type — one of `enrichment-types`.

   Returns the schema map on creation, or `:already-exists`."
  [pipeline-config enrichment-type]
  (let [base-names (pipeline-coll/pipeline-collection-names pipeline-config)
        docs-coll  (:docs-collection base-names)
        enrich-coll (enrichment-collection-name pipeline-config enrichment-type)
        schema (schema-for enrichment-type [docs-coll enrich-coll])]
    (storage/create-collection! pipeline-config schema)))

(defn ensure-collection-by-name!
  "Name-only variant of `ensure-collection!` for callers that already
   have both collection names handy and don't want to round-trip
   through `pipeline-config`. analyze-corpus uses this — it derives
   the enrichment name directly from the docs name, so re-resolving a
   pipeline-config just to land back at the same two strings is wasted
   work.

   Args:
     docs-collection-name       — base docs collection (string)
     enrichment-collection-name — target enrichment collection (string)
     enrichment-type            — one of `enrichment-types`

   Idempotent (delegates to `storage/create-collection!` which maps a
   Typesense 409 conflict to `:already-exists`)."
  [config docs-collection-name enrichment-collection-name enrichment-type]
  (let [schema (schema-for enrichment-type
                           [docs-collection-name enrichment-collection-name])]
    (storage/create-collection! config schema)))
