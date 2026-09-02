(ns digdir.skills.enrichment.apply-phrases
  "Phase D1 — :builtin/enrichment-apply-phrases

   Write proposed verified phrases to the parallel
   `enrichment_verified_phrases_<hash>` Typesense collection.
   Idempotent on `chunk-id`: each apply call deletes any prior rows
   for the supplied chunk-ids first, then upserts the new ones.

   Mirror of `:builtin/enrichment-apply-questions`. The shape is the
   same; only the Typesense field name differs (`phrase` vs `question`)
   and the proposal output key (`:phrases` vs `:questions`).

   Per-row failure surfacing (Typesense returns a vec of
   `{:success bool ...}`) — never silently masquerade as success.

   The skill accepts a *vector* of proposals — typically one per chunk
   from a `:foreach` over `:builtin/enrichment-propose-phrases`. A
   batched delete + batched upsert keep the round-trip count to 2
   regardless of how many chunks are in the batch.

   Lives in `src-dev/` for the same reason as the other Phase B/D
   skills: offline tooling, not part of the runtime retrieval path."
  (:require [clojure.string :as str]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def apply-phrases-metadata
  {:skill-id :builtin/enrichment-apply-phrases
   :name "Apply verified phrases"
   :description "Write proposed verified phrases to a parallel Typesense enrichment collection. Idempotent on chunk-id (deletes prior rows for the supplied chunk-ids before upserting)."
   :category :augmentation
   ;; Only :collection-name is strictly required. The skill body
   ;; accepts EITHER :proposals (vec) OR :proposal (singular map,
   ;; graph caller). See apply-questions for the rationale.
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
   provenance — typically an agent that composed the phrases inline
   instead of going through the propose-phrases skill."
  "agent-composed")

(defn- effective-provenance
  "Fill in defaults for any provenance fields the caller didn't supply."
  [provenance]
  {:model (or (:model provenance) agent-composed-model-tag)
   :prompt-hash (:prompt-hash provenance)
   :generated-at-ms (or (:generated-at-ms provenance)
                        (System/currentTimeMillis))})

(defn proposals->rows
  "Flatten a vector of phrase proposals
     [{:chunk-id :doc-num :phrases [..] :provenance {...}} ...]
   into the Typesense row shape the schema expects:
     [{:chunk_id :doc_num :phrase :model :prompt_hash :generated_at} ...]"
  [proposals]
  (vec
   (for [{:keys [chunk-id doc-num phrases provenance]} proposals
         :let [prov (effective-provenance provenance)]
         p phrases
         :when (and (string? p) (seq p))]
     (cond-> {:chunk_id chunk-id
              :doc_num (str (or doc-num ""))
              :phrase p
              :model (:model prov)
              :generated_at (:generated-at-ms prov)}
       (:prompt-hash prov) (assoc :prompt_hash (:prompt-hash prov))))))

(defn chunk-ids-filter
  "Build the Typesense `filter_by` clause that targets every chunk-id
   in `chunk-ids`. Returns nil for an empty collection so callers can
   short-circuit the delete call. Bracket-list form is what Typesense
   actually matches for non-numeric string fields (same lesson learned
   in revert-chunk during Phase C.5 validation)."
  [chunk-ids]
  (let [ids (->> chunk-ids
                 (remove nil?)
                 distinct
                 vec)]
    (when (seq ids)
      (str "chunk_id:=["
           (str/join "," ids)
           "]"))))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-apply-phrases
  "Apply the batch of phrase proposals against `:collection-name`.

   Inputs:
     :proposals       — vec of {:chunk-id :doc-num :phrases [...] :provenance {...}}
                        OR
     :proposal        — singular map (graph caller; backfilled with :doc-num
                        from the separate `:doc-num` input if absent)
     :doc-num         — optional, backfills :proposal's :doc-num
     :collection-name — fully-qualified Typesense collection name

   Parameters:
     :dry-run? — when true, returns what would be applied without calling Typesense

   Outputs:
     :applied-count   — number of rows ACTUALLY upserted (per Typesense success flags)
     :chunk-ids       — distinct chunk-ids touched
     :collection-name — echo of the target collection
     :rows            — only when dry-run?"
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [proposals proposal collection-name doc-num]} inputs
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
                      {:skill-id :builtin/enrichment-apply-phrases}))

      (empty? rows)
      (skills/success-result
       {:applied-count 0
        :chunk-ids []
        :collection-name collection-name}
       {:note "No phrases in proposals — nothing to apply."})

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
              (when-not (= 404 (:status (ex-data e)))
                (throw e)))))
        (let [resp (ts/upsert-documents! settings collection-name rows)
              row-results (when (sequential? resp) resp)
              successes (filter :success row-results)
              failures (remove :success row-results)]
          (if (seq failures)
            (throw (ex-info "Typesense upsert had row-level failures"
                            {:skill-id :builtin/enrichment-apply-phrases
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

(def apply-phrases-skill
  {:metadata apply-phrases-metadata
   :execute execute-apply-phrases})

(defn register!
  "Register the apply-phrases skill. Idempotent."
  []
  (skills/register-skill! apply-phrases-skill))

(register!)
