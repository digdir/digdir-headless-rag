(ns digdir.skills.enrichment.verify-prune
  "`:builtin/enrichment-verify-prune` — the PRUNE-side gate.

   The mirror image of `:builtin/enrichment-verify-retrieval`. Where verify
   asks *did ADDING this phrase create a retrieval path?*, this asks *is it SAFE
   and USEFUL to REMOVE this phrase?* — the bridge-vs-noise decision the IDF
   metric cannot make (it can't tell a deliberate broad bridge from generic noise;
   only retrieval can).

   ## Role: SAFETY only (broadness is upstream, net-benefit is downstream)

   This skill is ONE of three stages and deliberately narrow:
   - **propose-prune (token-IDF)** decides *broadness* — which phrases are
     candidates at all. A per-op retrieval probe CANNOT carry this: the hybrid
     phrase lookup's vector half returns ~15-20 hits for any phrase, so an
     off-target COUNT does not separate broad from specific (verified 2026-06-03:
     broad 16-19 vs specific 11-15, fully overlapping). IDF over the phrase→doc
     graph does separate them cleanly, so that is where broadness lives.
   - **verify-prune (here)** decides *removal safety*: query retrieval **with `p`
     itself** and ask — is `c` still findable WITHOUT `p`, via a sibling phrase
     (`:c-via-other-phrase?`) or plain content search (`:c-via-content?`)? If `c`
     reaches retrieval ONLY through `p` it is STRANDED → KEEP (even though
     propose-prune flagged `p` broad). Otherwise removal is safe.
   - **eval-suite (batch, enrichment-ON benchmark)** decides *net benefit* — the
     faithful arbiter on whether a prune batch actually improves retrieval.

   `safe-to-prune? = (not stranded?)`. In the graph only broad candidates reach
   this skill, so `:prune?` mirrors `:safe-to-prune?`.

   Reuses `rag/lookup-search-phrases-similar` (now returning :matched-phrase) and
   `rag/search-chunks-by-content` — the same plumbing verify-retrieval uses, just
   read in the opposite direction.

   Lives in `src-dev/` alongside its enrichment siblings."
  (:require [clojure.string :as str]
            [digdir.rag.retrieval :as rag]
            [digdir.rag.skills.core :as skills]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def verify-prune-metadata
  {:skill-id :builtin/enrichment-verify-prune
   :name "Verify phrase prune safety"
   :description "SAFETY gate for the prune side: for a (propose-prune-flagged broad) phrase on a chunk, query retrieval with the phrase and decide whether removing it would STRAND the chunk (only path) or is safe (chunk still findable via a sibling phrase or content). Broadness is established upstream by IDF; net benefit by the batch eval-suite."
   :category :validation
   :inputs [:chunk-id :candidate-phrase :phrases-collection-name
            :chunks-collection :docs-collection]
   :outputs [:candidate-phrase :chunk-id
             :c-via-p? :c-via-other-phrase? :c-via-content?
             :stranded? :safe-to-prune?
             :prune? :keep? :summary]
   :parameters {:limit :number}
   :required-services #{:typesense}
   :version "1.0.0"
   :tags #{:enrichment :self-improve :prune :validation}})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- norm
  "Loose phrase equality — trim + lowercase, so a matched-phrase compares equal
   to the candidate regardless of incidental whitespace/case."
  [s]
  (some-> s str/trim str/lower-case))

(defn- summarise
  [{:keys [c-via-p? c-via-other-phrase? c-via-content? stranded? prune?]}]
  (str (if c-via-p? "owner-via-p" "owner-not-via-p")
       " · alt-path " (cond c-via-other-phrase? "other-phrase"
                            c-via-content?      "content"
                            :else               "none")
       " · " (if stranded? "STRANDED" "not-stranded")
       " → " (if prune? "PRUNE-safe" "keep (stranded)")))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-verify-prune
  "Decide whether candidate phrase `p` may be pruned from chunk `c`.

   Inputs:
     :chunk-id               — c, the phrase's owning chunk
     :candidate-phrase       — p, the phrase string under consideration
     :phrases-collection-name — the (combined/primary) phrases collection to probe
     :chunks-collection       — for the content-search fallback path
     :docs-collection         — filter scope for the lookups

   Parameters:
     :limit — hits per lookup (default 20)

   Outputs: see metadata + ns docstring."
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [chunk-id candidate-phrase phrases-collection-name
                chunks-collection docs-collection]} inputs
        {:keys [limit]} parameters
        tenant (:tenant skill-params)
        skip-reason
        (cond
          (or (nil? candidate-phrase) (str/blank? (str candidate-phrase)))
          "missing :candidate-phrase"
          (or (nil? chunk-id) (str/blank? (str chunk-id)))
          "missing :chunk-id"
          (or (nil? phrases-collection-name) (str/blank? (str phrases-collection-name)))
          "missing :phrases-collection-name"
          :else nil)]
    (if skip-reason
      ;; Conservative on missing inputs: do NOT prune (keep the phrase).
      (skills/success-result
       {:candidate-phrase candidate-phrase :chunk-id chunk-id
        :c-via-p? nil :c-via-other-phrase? nil :c-via-content? nil
        :stranded? nil :safe-to-prune? false
        :prune? false :keep? true
        :summary (str "verify-prune-skip · " skip-reason " · → keep")}
       {:skipped? true :reason skip-reason})
      (let [opts (cond-> {:limit (or limit 20)}
                   tenant (assoc :tenant tenant))
            ;; Query retrieval WITH the phrase itself.
            phrase-hits (rag/lookup-search-phrases-similar
                         phrases-collection-name docs-collection
                         [candidate-phrase] nil opts)
            content-hits (if (and chunks-collection (seq chunks-collection))
                           (rag/search-chunks-by-content
                            chunks-collection docs-collection
                            [candidate-phrase] nil opts)
                           nil)
            cand (norm candidate-phrase)
            c-hits (filter #(= chunk-id (:chunk_id %)) phrase-hits)
            ;; Did c surface via p exactly? via a DIFFERENT phrase? via content?
            c-via-p? (boolean (some #(= cand (norm (:matched-phrase %))) c-hits))
            c-via-other-phrase? (boolean (some #(not= cand (norm (:matched-phrase %))) c-hits))
            c-via-content? (boolean (and content-hits
                                         (some #(= chunk-id (:chunk_id %)) content-hits)))
            ;; Stranded = c reaches retrieval for this (phrase-derived) query ONLY
            ;; through p — no sibling phrase, no content path. Removing p would then
            ;; strand c, so KEEP even though propose-prune flagged p as broad.
            stranded? (and c-via-p? (not c-via-other-phrase?) (not c-via-content?))
            ;; SAFETY gate only. Broadness is established upstream by propose-prune
            ;; (token-IDF) — a per-op retrieval probe can't carry it: the hybrid
            ;; lookup's vector half returns ~15-20 hits for ANY phrase, so an
            ;; off-target COUNT does not separate broad from specific (verified
            ;; 2026-06-03: broad 16-19 vs specific 11-15, overlapping). verify-prune
            ;; therefore answers only "is removing this flagged-broad phrase SAFE?"
            ;; The batch eval-suite (enrichment-ON benchmark) is the net-benefit judge.
            safe-to-prune? (not stranded?)
            base {:candidate-phrase candidate-phrase :chunk-id chunk-id
                  :c-via-p? c-via-p? :c-via-other-phrase? c-via-other-phrase?
                  :c-via-content? c-via-content?
                  :stranded? stranded?
                  :safe-to-prune? safe-to-prune?
                  ;; :prune? mirrors :safe-to-prune? for the graph :decide switch —
                  ;; only broad candidates (propose-prune output) reach this skill,
                  ;; so "safe" == "prune" in context.
                  :prune? safe-to-prune? :keep? (not safe-to-prune?)}]
        (skills/success-result
         (assoc base :summary (summarise base))
         {:tenant tenant
          :phrase-hits (count phrase-hits)
          :content-hits (count (or content-hits []))})))))

;; =============================================================================
;; Registration
;; =============================================================================

(def verify-prune-skill
  {:metadata verify-prune-metadata
   :execute execute-verify-prune})

(defn register!
  "Register the verify-prune skill. Idempotent."
  []
  (skills/register-skill! verify-prune-skill))

(register!)
