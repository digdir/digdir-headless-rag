(ns digdir.skills.enrichment.apply-prune
  "`:builtin/enrichment-apply-prune` — the PRUNE-side writer.

   The deletion counterpart of `:builtin/enrichment-apply-phrases`. Takes the
   APPROVED prune candidates (propose-prune flagged broad, verify-prune confirmed
   safe) and removes them from the phrase collections by exact row `:id` — which
   propose-prune captured from each row, so this works uniformly for primary
   (deterministic `sha256-short(chunk_id|phrase)` ids) and enrichment
   (Typesense-auto ids) alike.

   Operates on whatever collection names it's handed: in validation those are
   CLONES (rollback = drop the clone); in production they'd be the live
   collections. Each candidate carries `:collection` (:primary | :enrichment) and
   the skill is told the concrete name for each, so one call can prune across both.

   Idempotent: deleting an already-absent id is a no-op. Per-id failures (other
   than 404) surface rather than masquerade as success. Lives in `src-dev/`."
  (:require [clojure.string :as str]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def apply-prune-metadata
  {:skill-id :builtin/enrichment-apply-prune
   :name "Apply phrase prunes"
   :description "Delete approved prune candidates from the phrase collections by exact row id. Routes each candidate to its collection (:primary -> primary-collection-name, :enrichment -> enrichment-collection-name). Operates on clones for reversibility."
   :category :augmentation
   :inputs [:candidates :primary-collection-name :enrichment-collection-name]
   :outputs [:pruned-count :pruned-by-collection :collections]
   :parameters {:dry-run? :boolean}
   :required-services #{:typesense}
   :version "1.0.0"
   :tags #{:typesense :enrichment :self-improve :prune}})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- id-filter
  "Typesense `id:=[...]` clause for `ids`, or nil if empty. Bracket-list form is
   what TS matches for string id fields (same lesson as apply-phrases/revert)."
  [ids]
  (let [ids (->> ids (remove nil?) distinct vec)]
    (when (seq ids) (str "id:=[" (str/join "," ids) "]"))))

(defn- delete-by-ids!
  "Delete `ids` from `coll`. Tolerates 404 (collection/rows absent). Returns the
   count requested (TS delete-by-filter doesn't return per-id results, so we report
   the requested set; idempotent so re-runs are safe)."
  [settings coll ids]
  (if-let [flt (id-filter ids)]
    (do (try
          (ts/delete-documents! settings coll {:filter_by flt})
          (catch clojure.lang.ExceptionInfo e
            (when-not (= 404 (:status (ex-data e))) (throw e))))
        (count (distinct (remove nil? ids))))
    0))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-apply-prune
  "Delete the approved prune candidates from their collections.

   Inputs:
     :candidates                 — vec of {:id :collection (:primary|:enrichment) ...}
                                   (the verify-prune-approved subset)
     :primary-collection-name    — concrete name to delete :primary candidates from
     :enrichment-collection-name — concrete name to delete :enrichment candidates from
                                   (optional; :enrichment candidates skipped if absent)

   Parameters:
     :dry-run? — when true, report what WOULD be deleted without touching Typesense.

   Outputs:
     :pruned-count        — total rows deleted
     :pruned-by-collection — {:primary n :enrichment n}
     :collections         — echo of the targets used"
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [candidates primary-collection-name enrichment-collection-name]} inputs
        {:keys [dry-run?]} parameters
        tenant (:tenant skill-params)
        by-coll (group-by :collection (filterv :id candidates))
        primary-ids (mapv :id (get by-coll :primary))
        enrichment-ids (mapv :id (get by-coll :enrichment))
        enrichment-target (when (and enrichment-collection-name
                                     (seq (str enrichment-collection-name)))
                            enrichment-collection-name)
        targets {:primary primary-collection-name :enrichment enrichment-target}]
    (cond
      (empty? (filterv :id candidates))
      (skills/success-result
       {:pruned-count 0 :pruned-by-collection {:primary 0 :enrichment 0} :collections targets}
       {:note "no candidates with :id — nothing to prune"})

      dry-run?
      (skills/success-result
       {:pruned-count (+ (count primary-ids) (count enrichment-ids))
        :pruned-by-collection {:primary (count primary-ids) :enrichment (count enrichment-ids)}
        :collections targets}
       {:dry-run? true})

      :else
      (let [settings (ts-utils/make-ts-settings (when tenant {:tenant tenant}))
            _ (when (nil? settings)
                (throw (ex-info "No Typesense settings — tenant missing or unconfigured"
                                {:tenant tenant})))
            n-primary (if (and primary-collection-name (seq (str primary-collection-name)))
                        (delete-by-ids! settings primary-collection-name primary-ids)
                        0)
            n-enrichment (if enrichment-target
                           (delete-by-ids! settings enrichment-target enrichment-ids)
                           0)]
        (skills/success-result
         {:pruned-count (+ n-primary n-enrichment)
          :pruned-by-collection {:primary n-primary :enrichment n-enrichment}
          :collections targets}
         {:tenant tenant
          :primary-requested (count primary-ids)
          :enrichment-requested (count enrichment-ids)})))))

;; =============================================================================
;; Registration
;; =============================================================================

(def apply-prune-skill
  {:metadata apply-prune-metadata
   :execute execute-apply-prune})

(defn register!
  "Register the apply-prune skill. Idempotent."
  []
  (skills/register-skill! apply-prune-skill))

(register!)
