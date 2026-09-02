(ns digdir.skills.enrichment.apply-facts
  "Phase D2 — :builtin/enrichment-apply-facts

   Write proposed (subject, predicate, object) fact triples to the
   parallel `enrichment_fact_assertions_<hash>` Typesense collection.
   Idempotent on `chunk-id`: each apply call deletes any prior rows
   for the supplied chunk-ids first, then upserts the new ones.

   Mirror of `:builtin/enrichment-apply-phrases`. The shape is the
   same; only the row schema differs — each triple becomes a row with
   subject/predicate/object fields plus a synthesized `triple_text`
   that the schema's `triple_vec` embedding pulls from.

   Per-row failure surfacing (Typesense returns a vec of
   `{:success bool ...}`) — never silently masquerade as success."
  (:require [clojure.string :as str]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.enrichment.propose-facts :as propose-facts]
            [typesense.client :as ts]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def apply-facts-metadata
  {:skill-id :builtin/enrichment-apply-facts
   :name "Apply fact assertions"
   :description "Write proposed (subject, predicate, object) triples to a parallel Typesense enrichment collection. Idempotent on chunk-id (deletes prior rows for the supplied chunk-ids before upserting)."
   :category :augmentation
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
   provenance — typically an agent that composed the facts inline
   instead of going through the propose-facts skill."
  "agent-composed")

(defn- effective-provenance
  "Fill in defaults for any provenance fields the caller didn't supply."
  [provenance]
  {:model (or (:model provenance) agent-composed-model-tag)
   :prompt-hash (:prompt-hash provenance)
   :generated-at-ms (or (:generated-at-ms provenance)
                        (System/currentTimeMillis))})

(defn- valid-triple?
  "A triple counts as applyable only when subject/predicate/object are
   all present non-blank strings. Anything else gets dropped silently
   here — the propose-facts parser already drops malformed rows, but
   agent-composed proposals can still trickle through."
  [{:keys [subject predicate object]}]
  (and (string? subject) (seq (str/trim subject))
       (string? predicate) (seq (str/trim predicate))
       (string? object) (seq (str/trim object))))

(defn proposals->rows
  "Flatten a vector of fact-assertion proposals
     [{:chunk-id :doc-num :facts [{:subject :predicate :object} ...] :provenance {...}} ...]
   into the Typesense row shape the schema expects:
     [{:chunk_id :doc_num :subject :predicate :object :triple_text
       :model :prompt_hash :generated_at} ...]

   Drops triples that are missing any of subject/predicate/object so
   the upsert never fails on incomplete rows."
  [proposals]
  (vec
   (for [{:keys [chunk-id doc-num facts provenance]} proposals
         :let [prov (effective-provenance provenance)]
         triple facts
         :when (valid-triple? triple)
         :let [trimmed {:subject (str/trim (:subject triple))
                        :predicate (str/trim (:predicate triple))
                        :object (str/trim (:object triple))}]]
     (cond-> {:chunk_id chunk-id
              :doc_num (str (or doc-num ""))
              :subject (:subject trimmed)
              :predicate (:predicate trimmed)
              :object (:object trimmed)
              :triple_text (propose-facts/triple-text trimmed)
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
      (str "chunk_id:=["
           (str/join "," ids)
           "]"))))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-apply-facts
  "Apply the batch of fact-assertion proposals against `:collection-name`.

   Inputs:
     :proposals       — vec of {:chunk-id :doc-num :facts [...] :provenance {...}}
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
                      {:skill-id :builtin/enrichment-apply-facts}))

      (empty? rows)
      (skills/success-result
       {:applied-count 0
        :chunk-ids []
        :collection-name collection-name}
       {:note "No fact assertions in proposals — nothing to apply."})

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
                            {:skill-id :builtin/enrichment-apply-facts
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

(def apply-facts-skill
  {:metadata apply-facts-metadata
   :execute execute-apply-facts})

(defn register!
  "Register the apply-facts skill. Idempotent."
  []
  (skills/register-skill! apply-facts-skill))

(register!)
