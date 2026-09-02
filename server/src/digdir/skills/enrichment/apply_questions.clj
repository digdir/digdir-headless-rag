(ns digdir.skills.enrichment.apply-questions
  "Phase B.3 — :builtin/enrichment-apply-questions

   Write proposed hypothetical questions to the parallel
   `enrichment_hypothetical_questions_<hash>` Typesense collection.
   Idempotent on `chunk-id`: each apply call deletes any prior rows
   for the supplied chunk-ids first, then upserts the new ones. This
   keeps the collection clean when the proposal prompt or count
   changes (no orphan rows pinned to stale prompt-hashes).

   The skill accepts a *vector* of proposals — typically one per chunk
   from a `:foreach` over `:builtin/enrichment-propose-questions`. A
   batched delete + batched upsert keep the round-trip count to 2
   regardless of how many chunks are in the batch.

   Lives in `src-dev/` for the same reason as the other Phase B
   skills: offline tooling, not part of the runtime retrieval path."
  (:require [clojure.string :as str]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def apply-questions-metadata
  {:skill-id :builtin/enrichment-apply-questions
   :name "Apply hypothetical questions"
   :description "Write proposed hypothetical questions to a parallel Typesense enrichment collection. Idempotent on chunk-id (deletes prior rows for the supplied chunk-ids before upserting)."
   :category :augmentation
   ;; Only :collection-name is strictly required. The skill body
   ;; accepts EITHER :proposals (vec — the ReAct caller's shape) OR
   ;; :proposal (singular map — the graph caller's shape, since the
   ;; runner can't construct an inline vec to pass a single proposal as
   ;; a one-element seq). Listing only :collection-name means the
   ;; runner's input validator passes for both styles.
   :inputs [:collection-name]
   :outputs [:applied-count :chunk-ids :collection-name]
   :parameters {:dry-run? :boolean}
   :required-services #{:typesense}
   :version "1.0.0"
   :tags #{:typesense :enrichment :self-improve}})

;; =============================================================================
;; Helpers
;; =============================================================================

(def ^:private agent-composed-model-tag
  "Marker placed in :model when the caller supplies a proposal without
   provenance — typically an agent that composed the questions inline
   instead of going through `:builtin/enrichment-propose-questions`.
   Lets later audit / regeneration logic still partition rows by their
   origin even when the structured propose path was skipped."
  "agent-composed")

(defn- effective-provenance
  "Fill in defaults for any provenance fields the caller didn't supply.
   The propose-questions skill stamps a real model name + prompt-hash;
   when an agent composes inline and skips that step, we still want the
   row to carry SOMETHING for audit and future-selective-regeneration.

   Contract: returned map is well-formed even when input is nil or {}.
   :prompt-hash is left nil intentionally — we don't have one to put
   there, and synthesizing a fake hash would defeat its purpose."
  [provenance]
  {:model (or (:model provenance) agent-composed-model-tag)
   :prompt-hash (:prompt-hash provenance)
   :generated-at-ms (or (:generated-at-ms provenance)
                        (System/currentTimeMillis))})

(defn proposals->rows
  "Flatten a vector of proposals
     [{:chunk-id :doc-num :questions [..] :provenance {...}} ...]
   into the Typesense row shape the schema expects:
     [{:chunk_id :doc_num :question :model :prompt_hash :generated_at} ...]

   Auto-stamps any provenance fields the caller didn't supply, so
   inline-composed proposals (without a prior propose-questions call)
   still produce audit-shaped rows. :model defaults to 'agent-composed',
   :generated-at-ms defaults to System/currentTimeMillis, :prompt-hash
   stays nil when absent."
  [proposals]
  (vec
   (for [{:keys [chunk-id doc-num questions provenance]} proposals
         :let [prov (effective-provenance provenance)]
         q questions
         :when (and (string? q) (seq q))]
     (cond-> {:chunk_id chunk-id
              :doc_num (str (or doc-num ""))
              :question q
              :model (:model prov)
              :generated_at (:generated-at-ms prov)}
       (:prompt-hash prov) (assoc :prompt_hash (:prompt-hash prov))))))

(defn chunk-ids-filter
  "Build the Typesense `filter_by` clause that targets every chunk-id
   in `chunk-ids`. Returns nil for an empty collection so callers can
   short-circuit the delete call."
  [chunk-ids]
  (let [ids (->> chunk-ids
                 (remove nil?)
                 distinct
                 vec)]
    (when (seq ids)
      ;; Typesense filter syntax: chunk_id:=[a,b,c]
      (str "chunk_id:=["
           (str/join "," ids)
           "]"))))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-apply-questions
  "Apply the batch of proposals against `:collection-name`.

   Inputs:
     :proposals       — vector of
                        {:chunk-id :doc-num :questions [...] :provenance {...}}
     :collection-name — fully-qualified Typesense collection name (the
                        caller computes this via
                        `digdir.skills.enrichment.collections/enrichment-collection-name`).

   Parameters:
     :dry-run? — when true, return what would be applied without
                 calling Typesense. Useful for tests and for the
                 self-improve agent's planning phase.

   Outputs:
     :applied-count    — number of rows upserted
     :chunk-ids        — distinct chunk-ids touched
     :collection-name  — echo of the target collection
     :rows             — only when dry-run? (so consumers can inspect)"
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [proposals proposal collection-name doc-num]} inputs
        ;; Graph callers wire `:proposal :propose` (a single proposal map
        ;; from `:builtin/enrichment-propose-questions`'s outputs); ReAct
        ;; callers pass `:proposals [...]` (a vec). Normalise here so the
        ;; downstream row-flattening doesn't have to care which shape
        ;; arrived.
        ;;
        ;; The optional `:doc-num` input (graph callers wire it from the
        ;; fetch-chunk-context step) backfills missing `:doc-num` on a
        ;; singular `:proposal`, because propose-questions doesn't emit
        ;; it and the Typesense schema declares `doc_num` non-optional.
        ;; Without this, every upsert errors with
        ;; `Error with field doc_num: Value cannot be empty.` and the
        ;; skill silently reports success (we don't check the per-row
        ;; status vec from `ts/upsert-documents!`).
        effective-proposals (cond
                              (seq proposals)
                              (vec proposals)

                              (map? proposal)
                              [(cond-> proposal
                                 (and doc-num (nil? (:doc-num proposal)))
                                 (assoc :doc-num doc-num))]

                              :else [])
        {:keys [dry-run?]} parameters
        tenant (:tenant skill-params)
        rows (proposals->rows effective-proposals)
        chunk-ids (->> effective-proposals (map :chunk-id) (remove nil?) distinct vec)]
    (cond
      (or (nil? collection-name) (empty? collection-name))
      (throw (ex-info "Missing :collection-name input"
                      {:skill-id :builtin/enrichment-apply-questions}))

      (empty? rows)
      (skills/success-result
       {:applied-count 0
        :chunk-ids []
        :collection-name collection-name}
       {:note "No questions in proposals — nothing to apply."})

      dry-run?
      (skills/success-result
       {:applied-count (count rows)
        :chunk-ids chunk-ids
        :collection-name collection-name
        :rows rows}
       {:dry-run? true})

      :else
      (let [settings (ts-utils/make-ts-settings (when tenant {:tenant tenant}))
            _ (when (nil? settings)
                (throw (ex-info "No Typesense settings — tenant missing or unconfigured"
                                {:tenant tenant})))
            del-filter (chunk-ids-filter chunk-ids)]
        (when del-filter
          (try
            (ts/delete-documents! settings collection-name {:filter_by del-filter})
            (catch clojure.lang.ExceptionInfo e
              ;; A "no documents matched" 404 is a successful no-op for
              ;; first-time application. Surface anything else.
              (when-not (= 404 (:status (ex-data e)))
                (throw e)))))
        (let [resp (ts/upsert-documents! settings collection-name rows)
              ;; Typesense returns a per-row vec: each entry has
              ;; `:success true/false` and (on failure) an `:error`
              ;; string. Aggregate so the skill returns the REAL
              ;; success count and surfaces row-level rejections —
              ;; without this, an "Error with field doc_num" gets
              ;; silently masked behind `applied-count (count rows)`.
              row-results (when (sequential? resp) resp)
              successes (filter :success row-results)
              failures (remove :success row-results)]
          (if (seq failures)
            (throw (ex-info "Typesense upsert had row-level failures"
                            {:skill-id :builtin/enrichment-apply-questions
                             :collection collection-name
                             :rows-sent (count rows)
                             :failed-count (count failures)
                             :first-error (-> failures first :error)
                             :sample-failures (->> failures (take 3) vec)}))
            (skills/success-result
             {:applied-count (count successes)
              :chunk-ids chunk-ids
              :collection-name collection-name}
             {:tenant tenant
              :delete-filter del-filter
              :rows-sent (count rows)
              :rows-accepted (count successes)})))))))

;; =============================================================================
;; Registration
;; =============================================================================

(def apply-questions-skill
  {:metadata apply-questions-metadata
   :execute execute-apply-questions})

(defn register!
  "Register the apply-questions skill. Idempotent."
  []
  (skills/register-skill! apply-questions-skill))

(register!)
