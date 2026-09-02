(ns digdir.skills.enrichment.collections-test
  "Phase B.1 — naming + schema invariants for parallel enrichment
   collections.

   We do not hit a live Typesense in these tests; `create-collection!`
   has its own integration coverage. What we verify here is purely the
   stuff that, if it drifts, silently joins enrichments to the wrong
   chunks or fails schema validation at runtime:

   1. The collection name is deterministic for a given pipeline-config
      and shares the hash with the base docs/chunks/phrases trio.
   2. The hypothetical-questions schema references the base docs
      collection on `doc_num`, so Typesense joins work.
   3. The schema dispatcher rejects unknown enrichment types loudly."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.docs.pipeline.storage :as storage]
            [digdir.pipeline.collections :as pipeline-coll]
            [digdir.skills.enrichment.collections :as enrich-coll]))

(def ^:private fixture-config
  {:pipeline-name "self-improve-test"
   :source-type :website
   :chunk-strategy :fixed
   :chunk-minimum-length 200
   :chunk-maximum-length 1000
   :search-phrases-model "gpt-4"
   :search-phrases-fallback "gpt-3.5"
   :search-phrases-prompt "extract phrases"})

(deftest collection-name-shares-base-hash
  (testing "Enrichment collection name reuses the base pipeline hash so it tracks the chunks it enriches"
    (let [base (pipeline-coll/pipeline-collection-names fixture-config)
          hash-val (pipeline-coll/pipeline-config-hash fixture-config)
          enrich-name (enrich-coll/enrichment-collection-name
                       fixture-config :hypothetical-questions)]
      (is (.endsWith enrich-name (str "_" hash-val))
          "Enrichment name ends with the same hash as base chunks/docs/phrases")
      (is (.contains enrich-name "enrichment_hypothetical_questions_")
          "Enrichment name carries the type segment")
      (is (.startsWith enrich-name "self_improve_test_")
          "Enrichment name carries the auto-generated prefix from pipeline-name")
      ;; Cross-check: changing the chunking config moves both base and
      ;; enrichment to a new hash together.
      (let [bumped (assoc fixture-config :chunk-maximum-length 999)
            bumped-base (pipeline-coll/pipeline-collection-names bumped)
            bumped-enrich (enrich-coll/enrichment-collection-name
                            bumped :hypothetical-questions)]
        (is (not= (:chunks-collection base) (:chunks-collection bumped-base)))
        (is (not= enrich-name bumped-enrich))
        (is (.endsWith bumped-enrich
                       (str "_" (pipeline-coll/pipeline-config-hash bumped))))))))

(deftest collection-names-map
  (testing "Bulk helper returns one entry per requested enrichment type"
    (let [m (enrich-coll/enrichment-collection-names
             fixture-config
             [:hypothetical-questions])]
      (is (= #{:hypothetical-questions} (set (keys m))))
      (is (= (enrich-coll/enrichment-collection-name
              fixture-config :hypothetical-questions)
             (:hypothetical-questions m))))))

(deftest unknown-enrichment-type-throws
  (testing "Naming and schema dispatch both reject unknown enrichment types"
    (is (thrown? clojure.lang.ExceptionInfo
                 (enrich-coll/enrichment-collection-name
                  fixture-config :bogus)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (enrich-coll/schema-for :bogus ["docs" "enrich"])))))

(deftest hypothetical-questions-schema-shape
  (testing "Schema has the fields the retrieval-side sibling strategy will need"
    (let [docs-coll "self_improve_test_documents_abc123"
          enrich-coll "self_improve_test_enrichment_hypothetical_questions_abc123"
          schema (enrich-coll/hypothetical-questions-schema
                  [docs-coll enrich-coll])
          field-by-name (into {} (map (juxt :name identity)) (:fields schema))]
      (is (= enrich-coll (:name schema)))
      (is (= "chunk_id" (:default_sorting_field schema)))
      ;; chunk_id keeps the join from base chunks honest.
      (is (= "string" (get-in field-by-name ["chunk_id" :type])))
      ;; doc_num joins back to the base docs collection.
      (is (= (str docs-coll ".doc_num")
             (get-in field-by-name ["doc_num" :reference])))
      ;; question text + its auto-embedded vector are both present.
      (is (= "string" (get-in field-by-name ["question" :type])))
      (is (= "float[]" (get-in field-by-name ["question_vec" :type])))
      (is (= ["question"] (get-in field-by-name ["question_vec" :embed :from])))
      (is (= 384 (get-in field-by-name ["question_vec" :num_dim])))
      ;; Provenance fields exist (optional) so we can selectively
      ;; regenerate when prompts/models change without losing other rows.
      (is (true? (get-in field-by-name ["model" :optional])))
      (is (true? (get-in field-by-name ["prompt_hash" :optional])))
      (is (true? (get-in field-by-name ["generated_at" :optional])))
      ;; Regression guard: prompt_hash must be indexed. Was :index false
      ;; up to 2026-05-19, which made revert-by-prompt-hash silently
      ;; match zero rows in Typesense (no error). If anyone flips this
      ;; back to false, the revert path will look like it worked but
      ;; leave rows behind.
      (is (true? (get-in field-by-name ["prompt_hash" :index]))
          ":index true required so revert-chunk's filter_by matches"))))

(deftest verified-phrases-schema-prompt-hash-indexed
  (testing "Regression guard for both schemas: prompt_hash is indexed"
    (let [schema (enrich-coll/verified-phrases-schema
                  ["docs-x" "enrich-x"])
          field-by-name (into {} (map (juxt :name identity)) (:fields schema))]
      (is (true? (get-in field-by-name ["prompt_hash" :index]))
          "verified-phrases schema also needs :index true"))))

(deftest fact-assertions-schema-shape
  (testing "Schema carries triple fields, joins back to docs, and exposes a triple_vec embedding"
    (let [docs-coll "self_improve_test_documents_abc123"
          facts-coll "self_improve_test_enrichment_fact_assertions_abc123"
          schema (enrich-coll/fact-assertions-schema
                  [docs-coll facts-coll])
          field-by-name (into {} (map (juxt :name identity)) (:fields schema))]
      (is (= facts-coll (:name schema)))
      (is (= "chunk_id" (:default_sorting_field schema)))
      ;; chunk_id keeps the join from base chunks honest.
      (is (= "string" (get-in field-by-name ["chunk_id" :type])))
      ;; doc_num joins back to the base docs collection.
      (is (= (str docs-coll ".doc_num")
             (get-in field-by-name ["doc_num" :reference])))
      ;; Triple fields — each independently indexed so callers can
      ;; facet/filter by any one of them.
      (is (= "string" (get-in field-by-name ["subject" :type])))
      (is (true? (get-in field-by-name ["subject" :index])))
      (is (= "string" (get-in field-by-name ["predicate" :type])))
      (is (true? (get-in field-by-name ["predicate" :index])))
      (is (= "string" (get-in field-by-name ["object" :type])))
      (is (true? (get-in field-by-name ["object" :index])))
      ;; Synthesized text + auto-embedded vector for retrieval.
      (is (= "string" (get-in field-by-name ["triple_text" :type])))
      (is (true? (get-in field-by-name ["triple_text" :index])))
      (is (= "float[]" (get-in field-by-name ["triple_vec" :type])))
      (is (= ["triple_text"] (get-in field-by-name ["triple_vec" :embed :from])))
      (is (= 384 (get-in field-by-name ["triple_vec" :num_dim])))
      ;; Provenance optional, prompt_hash indexed (revert relies on it).
      (is (true? (get-in field-by-name ["model" :optional])))
      (is (true? (get-in field-by-name ["prompt_hash" :optional])))
      (is (true? (get-in field-by-name ["prompt_hash" :index]))
          "fact-assertions schema needs :index true on prompt_hash for revert-by-prompt-hash to match")
      (is (true? (get-in field-by-name ["generated_at" :optional]))))))

(deftest schema-for-dispatches-fact-assertions
  (testing "schema-for routes :fact-assertions to fact-assertions-schema"
    (let [coll-ids ["docs-y" "facts-y"]]
      (is (= (enrich-coll/fact-assertions-schema coll-ids)
             (enrich-coll/schema-for :fact-assertions coll-ids))))))

(deftest ensure-collection-by-name-builds-correct-schema
  (testing "ensure-collection-by-name! invokes storage/create-collection! with the schema we'd build manually"
    (let [docs "self_improve_test_documents_abc123"
          enrich "self_improve_test_enrichment_verified_phrases_abc123"
          captured (atom nil)]
      (with-redefs [storage/create-collection!
                    (fn [schema] (reset! captured schema) :stub)]
        (is (= :stub
               (enrich-coll/ensure-collection-by-name!
                docs enrich :verified-phrases))
            "Return value passes through from storage/create-collection!")
        (let [schema @captured]
          (is (some? schema) "create-collection! actually got called")
          (is (= enrich (:name schema))
              "Schema name matches the enrichment collection arg")
          (is (= (enrich-coll/verified-phrases-schema [docs enrich])
                 schema)
              "Schema is byte-identical to schema-for output"))))))
