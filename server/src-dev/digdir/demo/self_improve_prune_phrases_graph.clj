(ns digdir.demo.self-improve-prune-phrases-graph
  "`:docs/self-improve-prune-phrases-graph` — the UNIFIED add+prune self-improvement
   agent. Same intent → plan → analyze → foreach → compose shape as
   `:docs/self-improve-phrases-graph`, but each per-chunk pass now does BOTH halves
   of the loop, each independently gated:

     ADD   : propose-phrases → apply (enrichment) → eval → verify-retrieval → keep/revert
     PRUNE : enrichment-prune-chunk = propose-prune (IDF) → verify-prune (safety)
             → verify-prune-benefit (content-rank pre-filter + LLM confirm) → apply-prune

   The ADD half grows discriminative bridges; the PRUNE half removes corpus-generic
   crowders. Both write to whatever collection names they're handed — pass CLONE
   names for a reversible run, real names to ship. The prune branch is collapsed into
   the single `:builtin/enrichment-prune-chunk` step so the graph stays linear (no
   nested foreach over candidates).

   Lives in `src-dev/` like the skills it composes."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.skills.templates.core :as templates]
            ;; Force registration of every composed skill.
            [digdir.skills.builtin.query-planner]
            [digdir.skills.enrichment.analyze-corpus]
            [digdir.skills.enrichment.apply-phrases]
            [digdir.skills.enrichment.compose-report]
            [digdir.skills.enrichment.eval-delta]
            [digdir.skills.enrichment.extract-intent]
            [digdir.skills.enrichment.fetch-chunk-context]
            [digdir.skills.enrichment.mark-keep]
            [digdir.skills.enrichment.propose-phrases]
            [digdir.skills.enrichment.prune-chunk]
            [digdir.skills.enrichment.revert-chunk]
            [digdir.skills.enrichment.verify-retrieval]))

;; =============================================================================
;; Inner sub-graph: ADD then PRUNE for one chunk
;; =============================================================================

(def enrich-and-prune-one-chunk-graph
  {:id :docs/enrich-and-prune-one-chunk
   :inputs [:chunk-id
            :enrichment-collection-name
            :phrases-collection-name
            :chunks-collection
            :docs-collection
            :tenant
            :suite-file
            :user-query]
   :outputs [:chunk-id :decision :prune]
   :steps
   [;; ---- ADD half (mirror of :docs/enrich-one-chunk-phrases) ----
    {:id :fetch
     :skill :builtin/enrichment-fetch-chunk-context
     :inputs {:chunk-id :$chunk-id
              :tenant :$tenant
              :chunks-collection :$chunks-collection
              :docs-collection :$docs-collection}}
    {:id :propose
     :skill :builtin/enrichment-propose-phrases
     :inputs {:chunk-id :$chunk-id
              :chunk-content [:fetch :chunk-content]
              :doc-title [:fetch :doc-title]
              :doc-url [:fetch :doc-url]}}
    {:id :apply
     :skill :builtin/enrichment-apply-phrases
     :inputs {:proposal :propose
              :doc-num [:fetch :doc-num]
              :collection-name :$enrichment-collection-name}}
    {:id :eval
     :skill :builtin/enrichment-eval-suite
     :inputs {:suite-file :$suite-file
              :tenant :$tenant
              :dataset-config-key "public-docs"
              :tenant-config-key "default"
              :runtime-config-key "default"
              :agent-id "digdir.demo/self-improve-prune-phrases-graph"
              :fail-on-gate? false}}
    {:id :verify
     :skill :builtin/enrichment-verify-retrieval
     :inputs {:chunk-id :$chunk-id
              :enrichment-collection-name :$enrichment-collection-name
              :docs-collection :$docs-collection
              :chunks-collection :$chunks-collection
              :user-query :$user-query
              :eval-summary [:eval :summary]}
     :parameters {:enrichment-type :verified-phrases}}
    {:id :decide
     :select {:on [:verify :keep?]}
     :branches
     {true {:do {:skill :builtin/enrichment-mark-keep
                 :inputs {:chunk-id :$chunk-id :proposal :propose
                          :eval [:eval :summary] :verify :verify :context :fetch}}}
      false {:do {:skill :builtin/enrichment-revert-chunk
                  :inputs {:chunk-id :$chunk-id
                           :prompt-hash [:propose :provenance :prompt-hash]
                           :collection-name :$enrichment-collection-name
                           :proposal :propose :eval [:eval :summary]
                           :verify :verify :context :fetch}}}
      :default {:do {:skill :builtin/enrichment-revert-chunk
                     :inputs {:chunk-id :$chunk-id
                              :prompt-hash [:propose :provenance :prompt-hash]
                              :collection-name :$enrichment-collection-name
                              :proposal :propose :eval [:eval :summary]
                              :verify :verify :context :fetch}}}}}

    ;; ---- PRUNE half (one composed step) ----
    {:id :prune
     :skill :builtin/enrichment-prune-chunk
     :inputs {:chunk-id :$chunk-id
              :phrases-collection-name :$phrases-collection-name
              :enrichment-collection-name :$enrichment-collection-name
              :chunks-collection :$chunks-collection
              :docs-collection :$docs-collection}
     :parameters {:specificity-threshold 4.0
                  :content-rank-threshold 1
                  :use-llm? true
                  :dry-run? false}}]})

(def enrich-and-prune-one-chunk-skill-graph
  (templates/make-skill-graph
   :docs/enrich-and-prune-one-chunk
   "Self-improve — add + prune one chunk"
   "Per-chunk inner sub-graph: grow discriminative phrases (ADD, gated by verify-retrieval) and remove corpus-generic crowders (PRUNE, gated by safety + content-rank + LLM)."
   enrich-and-prune-one-chunk-graph
   {:version "1.0.0"
    :tags #{:demo :self-improve :enrichment :prune :graph-only}}))

;; =============================================================================
;; Outer graph: add+prune over the corpus
;; =============================================================================

(def self-improve-prune-phrases-graph
  {:id :docs/self-improve-prune-phrases-graph
   :inputs [:docs-collection :chunks-collection :phrases-collection :user-query]
   :outputs [:report :report-structured :chunk-outcomes :analysis]
   :steps
   [{:id :intent
     :skill :builtin/extract-user-intent
     :inputs {:user-query :$user-query}}
    {:id :plan-queries
     :skill :builtin/query-planner
     :inputs {:query [:intent :topic] :conversation-history []}
     :parameters {:max-phrases 6}}
    {:id :analyze
     :skill :builtin/enrichment-analyze-corpus
     :inputs {:docs-collection :$docs-collection
              :chunks-collection :$chunks-collection
              :max-chunks 10
              :exclude-already-enriched? false
              :user-query [:intent :topic]
              :queries [:plan-queries :queries]
              :explicit-chunk-ids [:intent :explicit-chunk-ids]}
     :parameters {:selection-mode :llm
                  :enrichment-type :verified-phrases}}
    {:id :per-chunk
     :foreach {:over [:analyze :chunk-ids] :as :chunk-id}
     :do {:sub-graph {:graph-id :docs/enrich-and-prune-one-chunk
                      :inputs {:chunk-id :$chunk-id
                               :enrichment-collection-name [:analyze :enrichment-collection-name]
                               :phrases-collection-name :$phrases-collection
                               :chunks-collection :$chunks-collection
                               :docs-collection :$docs-collection
                               :suite-file "test/fixtures/agent/altinn3_lansert_stability.edn"
                               :user-query [:intent :topic]}}}
     :collect-as :chunk-outcomes
     :on-error :default}
    {:id :compose
     :skill :builtin/enrichment-compose-report
     :inputs {:analysis :analyze
              :outcomes [:per-chunk :chunk-outcomes]}}]})

(def self-improve-prune-phrases-skill-graph
  (templates/make-skill-graph
   :docs/self-improve-prune-phrases-graph
   "Self-improve add+prune phrases (unified)"
   "Unified self-improvement agent: per chunk, grow discriminative phrases AND prune corpus-generic crowders, each independently eval-gated. Pass clone collection names for a reversible run."
   self-improve-prune-phrases-graph
   {:version "1.0.0"
    :tags #{:demo :self-improve :enrichment :prune :graph-only}
    :input-schema templates/agent-tool-input-schema}))

(defn register!
  "Register the unified inner sub-graph and outer graph. Idempotent."
  []
  (templates/register-skill-graph! enrich-and-prune-one-chunk-skill-graph)
  (templates/register-skill-graph! self-improve-prune-phrases-skill-graph))

(register!)

(defn all-required-skills-present?
  []
  (every? #(some? (skills/get-skill %))
          #{:builtin/enrichment-fetch-chunk-context
            :builtin/enrichment-propose-phrases
            :builtin/enrichment-apply-phrases
            :builtin/enrichment-eval-suite
            :builtin/enrichment-verify-retrieval
            :builtin/enrichment-mark-keep
            :builtin/enrichment-revert-chunk
            :builtin/enrichment-prune-chunk}))
