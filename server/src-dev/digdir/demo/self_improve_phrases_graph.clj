(ns digdir.demo.self-improve-phrases-graph
  "Phase D1 — `:docs/self-improve-phrases-graph`.

   Parallel to `:docs/self-improve-graph` (the hypothetical-
   questions variant), this graph enriches chunks with VERIFIED PHRASES
   instead. Same propose → apply → eval → decide rhythm, same intent
   extraction up front, same deterministic compose-report at the end.
   The only differences:

   - `:propose` invokes `:builtin/enrichment-propose-phrases` (short
     topical fragments) instead of propose-questions.
   - `:apply` invokes `:builtin/enrichment-apply-phrases` against the
     verified-phrases Typesense collection.
   - `:analyze` derives the verified-phrases collection name by
     passing `:enrichment-type :verified-phrases` as a parameter.
   - The eval step's retrieval-side opt-in uses the same
     `:enrichment-search-targets` parameter on `:builtin/retrieval`,
     which now dispatches on `:verified-phrases` to call
     `lookup-verified-phrases-similar` (D1.5).

   See `plans/in-progress/self-improvement-agent-plan.md` Phase D
   direction-shift for why this is built as a graph from day one
   rather than as a ReAct tool first.

   Lives in `src-dev/` because the enrichment skills it composes do too."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.skills.templates.core :as templates]
            ;; Force registration of the skills the graph composes.
            [digdir.skills.builtin.query-planner]
            [digdir.skills.enrichment.analyze-corpus]
            [digdir.skills.enrichment.apply-phrases]
            [digdir.skills.enrichment.compose-report]
            [digdir.skills.enrichment.eval-delta]
            [digdir.skills.enrichment.extract-intent]
            [digdir.skills.enrichment.fetch-chunk-context]
            [digdir.skills.enrichment.mark-keep]
            [digdir.skills.enrichment.propose-phrases]
            [digdir.skills.enrichment.revert-chunk]
            [digdir.skills.enrichment.verify-retrieval]))

;; =============================================================================
;; Inner sub-graph: enrich one chunk with verified phrases
;; =============================================================================

(def enrich-one-chunk-phrases-graph
  "Per-chunk sequence: fetch chunk content, propose verified phrases,
   apply them to the verified-phrases enrichment collection, run the
   eval suite, decide keep/revert.

   Mirror of `:docs/enrich-one-chunk` with phrase-flavoured
   skills + phrase-aware retrieval (the eval suite picks up phrases
   via the same `:enrichment-search-targets` opt-in)."
  {:id :docs/enrich-one-chunk-phrases
   :inputs [:chunk-id
            :enrichment-collection-name
            :chunks-collection
            :docs-collection
            :tenant
            :dataset-config-key
            :tenant-config-key
            :runtime-config-key
            :suite-file
            :user-query]
   :outputs [:chunk-id :decision :eval :proposal :verify]
   :steps
   [{:id :fetch
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
     ;; `:doc-num` required by the Typesense schema; propose-phrases
     ;; doesn't emit it, so we thread it from the fetch step.
     :inputs {:proposal :propose
              :doc-num [:fetch :doc-num]
              :collection-name :$enrichment-collection-name}}

    {:id :eval
     :skill :builtin/enrichment-eval-suite
     ;; `:dataset-config-key` hardcoded to "public-docs" — diagnostics
     ;; falls back through tenant-config-key when this is nil, and the
     ;; literal `"default"` tenant-config-key would then trip the
     ;; agent's allowed-dataset-scopes check (observed 2026-05-19 in
     ;; phrases-graph runs where the playground didn't thread the
     ;; dataset key). Same hardening applied to self-improve-graph.
     :inputs {:suite-file :$suite-file
              :tenant :$tenant
              :dataset-config-key "public-docs"
              :tenant-config-key "default"
              :runtime-config-key "default"
              :agent-id "digdir.demo/self-improve-phrases-graph"
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
     ;; D2.8 — composite gate. See self_improve_graph.clj for rationale.
     :select {:on [:verify :keep?]}
     :branches
     {true {:do {:skill :builtin/enrichment-mark-keep
                 :inputs {:chunk-id :$chunk-id
                          :proposal :propose
                          :eval [:eval :summary]
                          :verify :verify
                          :context :fetch}}}
      false {:do {:skill :builtin/enrichment-revert-chunk
                  :inputs {:chunk-id :$chunk-id
                           :prompt-hash [:propose :provenance :prompt-hash]
                           :collection-name :$enrichment-collection-name
                           :proposal :propose
                           :eval [:eval :summary]
                           :verify :verify
                           :context :fetch}}}
      :default {:do {:skill :builtin/enrichment-revert-chunk
                     :inputs {:chunk-id :$chunk-id
                              :prompt-hash [:propose :provenance :prompt-hash]
                              :collection-name :$enrichment-collection-name
                              :proposal :propose
                              :eval [:eval :summary]
                              :verify :verify
                              :context :fetch}}}}}]})

(def enrich-one-chunk-phrases-skill-graph
  (templates/make-skill-graph
   :docs/enrich-one-chunk-phrases
   "Self-improve — enrich one chunk with verified phrases"
   "Per-chunk inner sub-graph for the verified-phrases self-improve experiment: propose phrases, apply, eval, keep/revert based on gate."
   enrich-one-chunk-phrases-graph
   {:version "1.0.0"
    :tags #{:demo :self-improve :enrichment :graph-only}}))

;; =============================================================================
;; Outer graph: self-improve over the whole corpus (phrases)
;; =============================================================================

(def self-improve-phrases-graph
  "Top-level skill graph for the verified-phrases self-improve
   experiment: intent → analyze → foreach(per-chunk) → compose-report.
   Mirror of `:docs/self-improve-graph` retargeted at the
   verified-phrases enrichment collection."
  {:id :docs/self-improve-phrases-graph
   :inputs [:docs-collection :chunks-collection :user-query]
   :outputs [:report :report-structured :chunk-outcomes :analysis]
   :steps
   [{:id :intent
     :skill :builtin/extract-user-intent
     :inputs {:user-query :$user-query}}

    ;; D2.20 — query-planner expands the intent into a small batch of
    ;; relaxed queries; analyze-corpus uses them for multi-pass sampling.
    {:id :plan-queries
     :skill :builtin/query-planner
     ;; See self_improve_graph.clj for the empty-vec rationale.
     :inputs {:query [:intent :topic]
              :conversation-history []}
     :parameters {:max-phrases 6}}

    {:id :analyze
     :skill :builtin/enrichment-analyze-corpus
     :inputs {:docs-collection :$docs-collection
              :chunks-collection :$chunks-collection
              :max-chunks 10
              :exclude-already-enriched? true
              :user-query [:intent :topic]
              :queries [:plan-queries :queries]
              :explicit-chunk-ids [:intent :explicit-chunk-ids]}
     ;; `:enrichment-type :verified-phrases` is a literal parameter so
     ;; analyze derives the verified-phrases collection name (not the
     ;; default hypothetical-questions name).
     :parameters {:selection-mode :llm
                  :enrichment-type :verified-phrases}}

    {:id :per-chunk
     :foreach {:over [:analyze :chunk-ids]
               :as :chunk-id}
     :do {:sub-graph {:graph-id :docs/enrich-one-chunk-phrases
                      :inputs {:chunk-id :$chunk-id
                               :enrichment-collection-name [:analyze :enrichment-collection-name]
                               :chunks-collection :$chunks-collection
                               :docs-collection :$docs-collection
                               :suite-file "test/fixtures/agent/altinn3_lansert_stability.edn"
                               ;; D2.8 — see self_improve_graph.clj.
                               :user-query [:intent :topic]}}}
     :collect-as :chunk-outcomes
     :on-error :default}

    {:id :compose
     :skill :builtin/enrichment-compose-report
     :inputs {:analysis :analyze
              :outcomes [:per-chunk :chunk-outcomes]}}]})

(def self-improve-phrases-skill-graph
  (templates/make-skill-graph
   :docs/self-improve-phrases-graph
   "Self-improve verified phrases (graph variant)"
   "Phase D1 graph: same propose → apply → eval → decide structure as :docs/self-improve-graph, but enriching chunks with verified phrases (short topical fragments) instead of hypothetical questions."
   self-improve-phrases-graph
   {:version "1.0.0"
    :tags #{:demo :self-improve :enrichment :graph-only}
    :input-schema templates/agent-tool-input-schema}))

;; =============================================================================
;; Playground agent definition
;; =============================================================================

;; =============================================================================
;; Registration
;; =============================================================================
;; Per-demo agent removed in Phase 0 — :builtin/docs-agent (agents/core.clj)
;; owns docs/self-improve-phrases-graph along with the other docs/* graphs.

(defn register!
  "Register both the inner sub-graph and the outer graph for the
   verified-phrases variant. Idempotent."
  []
  ;; Register the skills this graph dispatches to, not just the graph itself.
  ;; Requiring their namespaces registers them at load time, but a second
  ;; require after a registry reset is a no-op - so a graph could be registered
  ;; while its skills could not resolve, and the failure surfaced at invocation
  ;; instead of registration (#91). The thing that names a dependency is the
  ;; thing that pulls it in.
  (digdir.skills.builtin.query-planner/register!)
  (digdir.skills.enrichment.analyze-corpus/register!)
  (digdir.skills.enrichment.compose-report/register!)
  (digdir.skills.enrichment.eval-delta/register!)
  (digdir.skills.enrichment.extract-intent/register!)
  (digdir.skills.enrichment.fetch-chunk-context/register!)
  (digdir.skills.enrichment.mark-keep/register!)
  (digdir.skills.enrichment.propose-phrases/register!)
  (digdir.skills.enrichment.apply-phrases/register!)
  (digdir.skills.enrichment.revert-chunk/register!)
  (digdir.skills.enrichment.verify-retrieval/register!)
  (templates/register-skill-graph! enrich-one-chunk-phrases-skill-graph)
  (templates/register-skill-graph! self-improve-phrases-skill-graph))

(register!)

(defn all-required-skills-present?
  "Returns true iff every skill-id referenced by the inner sub-graph
   is registered. Useful for the test suite to fail fast."
  []
  (let [referenced #{:builtin/enrichment-fetch-chunk-context
                     :builtin/enrichment-propose-phrases
                     :builtin/enrichment-apply-phrases
                     :builtin/enrichment-eval-suite
                     :builtin/enrichment-verify-retrieval
                     :builtin/enrichment-mark-keep
                     :builtin/enrichment-revert-chunk}]
    (every? #(some? (skills/get-skill %)) referenced)))
