(ns digdir.skills.enrichment.mark-keep
  "`:builtin/enrichment-mark-keep` — record a 'keep' verdict for a chunk
   whose enrichment passed the eval-delta gate.

   No side effects. The Typesense rows were already written by
   `:builtin/enrichment-apply-questions`; this skill exists so the
   keep-branch of the self-improve graph's per-chunk `:select` has a
   symmetric output shape with the revert-branch. The `:foreach` step
   that wraps each chunk's sub-graph then collects a uniform vec of
   outcome maps that `:builtin/enrichment-compose-report` walks.

   Lives in `src-dev/` alongside its siblings — offline tooling, not
   part of the runtime retrieval path."
  (:require [digdir.rag.skills.core :as skills]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def mark-keep-metadata
  {:skill-id :builtin/enrichment-mark-keep
   :name "Mark chunk enrichment as kept"
   :description "Record a 'keep' verdict for a chunk whose enrichment passed the eval-delta gate. Pure tagging — no Typesense or DB side effects."
   :category :orchestration
   ;; Only :chunk-id is required. :proposal, :eval, :verify, :context
   ;; are pass-throughs (D2.16 added :context — the fetch-step outputs
   ;; so the report can render the chunk's source text in a details
   ;; block). All optional — absence is fine; compose-report renders
   ;; gracefully without them.
   :inputs [:chunk-id]
   :outputs [:decision :chunk-id :proposal :eval :verify :context]
   :parameters {}
   :version "1.1.0"
   :tags #{:enrichment :self-improve :graph-only}})

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-mark-keep
  "Emit `{:decision :keep ...}` plus pass-through of the inputs the
   self-improve graph wants threaded into its per-chunk outcome map.

   `:proposal`, `:eval`, `:verify`, and (D2.16) `:context` are echoed
   verbatim so downstream `:builtin/enrichment-compose-report` can
   cite the enrichment, the eval-summary, the verify signal, and the
   source chunk text (the context map carries the whole fetch-step
   outputs: chunk-content, doc-title, doc-url, doc-num)."
  [{:keys [inputs]}]
  (let [{:keys [chunk-id proposal eval verify context]} inputs]
    (when (or (nil? chunk-id) (and (string? chunk-id) (empty? chunk-id)))
      (throw (ex-info "Missing :chunk-id input"
                      {:skill-id :builtin/enrichment-mark-keep})))
    (skills/success-result
     (cond-> {:decision :keep
              :chunk-id chunk-id
              :proposal proposal
              :eval eval
              :verify verify}
       (some? context) (assoc :context context))
     {})))

;; =============================================================================
;; Registration
;; =============================================================================

(def mark-keep-skill
  {:metadata mark-keep-metadata
   :execute execute-mark-keep})

(defn register!
  "Register the mark-keep skill. Idempotent."
  []
  (skills/register-skill! mark-keep-skill))

(register!)
