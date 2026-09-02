(ns digdir.skills.enrichment.revert-chunk
  "`:builtin/enrichment-revert-chunk` — delete enrichment rows for a single
   chunk from a parallel Typesense enrichment collection.

   Symmetric to `:builtin/enrichment-apply-questions`. The graph version
   of the self-improve agent uses this as the inner sub-graph's revert
   branch when the eval-delta gate fails for an enriched chunk.

   Scope:
   - Always scoped by `:chunk-id` (single chunk).
   - Optionally narrowed by `:prompt-hash` so a caller can revert only
     the rows produced by one specific propose call, leaving rows from
     other prompt-hashes intact. The risk register on the plan flagged
     this as the guard against accidentally wiping unrelated history.
   - Idempotent on a 404 'no documents matched' response from Typesense:
     reverting an already-empty chunk is a successful no-op.

   Lives in `src-dev/` alongside the rest of the Phase B enrichment
   skills — offline tooling, not part of the runtime retrieval path."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def revert-chunk-metadata
  {:skill-id :builtin/enrichment-revert-chunk
   :name "Revert enrichment rows for a chunk"
   :description "Delete enrichment rows for a single chunk from a Typesense enrichment collection. Optionally scoped to one prompt-hash. Idempotent — reverting an already-empty chunk is a no-op. Echoes :proposal/:eval/:verify so the report can explain WHY the revert happened."
   :category :augmentation
   ;; :prompt-hash is OPTIONAL. :proposal, :eval, :verify are also
   ;; optional pass-throughs (D2.13) — when supplied, they're echoed
   ;; verbatim in the outputs so compose-report's reverted-block can
   ;; render the same proposal content + verify delta that the
   ;; kept-block does. Mirrors the mark-keep echo pattern.
   :inputs [:chunk-id :collection-name]
   :outputs [:decision :reverted-count :chunk-id :collection-name
             :filter-by :proposal :eval :verify :context]
   :parameters {:dry-run? :boolean}
   :required-services #{:typesense}
   :version "1.1.0"
   :tags #{:typesense :enrichment :self-improve}})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn build-filter-by
  "Build the Typesense `filter_by` clause for a chunk-scoped revert.

   With `prompt-hash`:    chunk_id:=[<id>] && prompt_hash:=[<hash>]
   Without `prompt-hash`: chunk_id:=[<id>]

   The bracket-list form (`field:=[value]`) is what Typesense actually
   matches for string fields — the bare `field:=value` form silently
   matches nothing for non-numeric ids. This is the same shape
   apply-questions uses for its pre-upsert delete filter
   (`chunk_id:=[c1,c2,c3]`).

   We assume chunk-ids and prompt-hashes are safe identifiers (slugs /
   hex). Callers that need to revert ids containing commas / brackets /
   quotes would need a different approach."
  [chunk-id prompt-hash]
  (let [base (str "chunk_id:=[" chunk-id "]")]
    (if (and (string? prompt-hash) (seq prompt-hash))
      (str base " && prompt_hash:=[" prompt-hash "]")
      base)))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-revert-chunk
  "Delete enrichment rows for `:chunk-id` from `:collection-name`,
   optionally narrowed by `:prompt-hash`.

   Inputs:
     :chunk-id        — single chunk identifier (required)
     :collection-name — fully-qualified Typesense collection name
                        (required; caller computes via
                        `digdir.skills.enrichment.collections/enrichment-collection-name`)
     :prompt-hash     — optional. When non-blank, scopes the delete to
                        rows whose `prompt_hash` field matches.

   Parameters:
     :dry-run? — when true, returns the planned filter without calling
                 Typesense.

   Skill-params:
     :tenant — used to build Typesense settings.

   Outputs:
     :reverted-count   — Typesense `num_deleted`; 0 on dry-run or 404
     :chunk-id         — echo of input
     :collection-name  — echo of input
     :filter-by        — the filter clause used (handy for trace)"
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [chunk-id collection-name prompt-hash
                proposal eval verify context]} inputs
        {:keys [dry-run?]} parameters
        tenant (:tenant skill-params)
        ;; D2.13/D2.16 — echo proposal/eval/verify/context into the
        ;; outputs so the report's reverted-block can show what was
        ;; tried, why verify said it didn't help, and the original
        ;; chunk source text in a <details> block. nil when the caller
        ;; didn't supply them.
        echo-outputs (cond-> {}
                       (some? proposal) (assoc :proposal proposal)
                       (some? eval)     (assoc :eval eval)
                       (some? verify)   (assoc :verify verify)
                       (some? context)  (assoc :context context))]
    (cond
      (or (nil? collection-name) (and (string? collection-name) (empty? collection-name)))
      (throw (ex-info "Missing :collection-name input"
                      {:skill-id :builtin/enrichment-revert-chunk}))

      (or (nil? chunk-id) (and (string? chunk-id) (empty? chunk-id)))
      (throw (ex-info "Missing :chunk-id input"
                      {:skill-id :builtin/enrichment-revert-chunk}))

      :else
      (let [filter-by (build-filter-by chunk-id prompt-hash)]
        (if dry-run?
          (skills/success-result
           (merge {:decision :revert
                   :reverted-count 0
                   :chunk-id chunk-id
                   :collection-name collection-name
                   :filter-by filter-by}
                  echo-outputs)
           {:dry-run? true})
          (let [settings (ts-utils/make-ts-settings (when tenant {:tenant tenant}))
                _ (when (nil? settings)
                    (throw (ex-info "No Typesense settings — tenant missing or unconfigured"
                                    {:tenant tenant})))
                resp (try
                       (ts/delete-documents! settings collection-name
                                             {:filter_by filter-by})
                       (catch clojure.lang.ExceptionInfo e
                         ;; A 404 'no documents matched' is a successful
                         ;; no-op for an already-empty chunk — same
                         ;; idempotency pattern apply-questions uses for
                         ;; its pre-upsert delete.
                         (if (= 404 (:status (ex-data e)))
                           {:num_deleted 0}
                           (throw e))))]
            (skills/success-result
             (merge {:decision :revert
                     :reverted-count (or (:num_deleted resp) 0)
                     :chunk-id chunk-id
                     :collection-name collection-name
                     :filter-by filter-by}
                    echo-outputs)
             {:tenant tenant})))))))

;; =============================================================================
;; Registration
;; =============================================================================

(def revert-chunk-skill
  {:metadata revert-chunk-metadata
   :execute execute-revert-chunk})

(defn register!
  "Register the revert-chunk skill. Idempotent."
  []
  (skills/register-skill! revert-chunk-skill))

(register!)
