(ns digdir.demo.self-improve-facts-graph
  "Phase D2 — `:docs/self-improve-facts-graph`.

   Parallel to `:docs/self-improve-graph` (questions) and
   `:docs/self-improve-phrases-graph` (phrases), this graph
   enriches chunks with FACT ASSERTIONS — (subject, predicate, object)
   triples extracted from the chunk content. Same propose → apply →
   eval → decide rhythm, same intent extraction up front, same
   deterministic compose-report at the end.

   What changes from the phrases variant:
   - `:propose` invokes `:builtin/enrichment-propose-facts` (triples).
   - `:apply` invokes `:builtin/enrichment-apply-facts` against the
     fact-assertions Typesense collection.
   - `:analyze` derives the fact-assertions collection name via
     `:enrichment-type :fact-assertions`.
   - The eval step's `:enrichment-search-targets` opt-in is wired
     elsewhere — `:builtin/retrieval` now dispatches on
     `:fact-assertions` to call `lookup-fact-assertions-similar`
     with exact-match-biased querying (D2.5).

   Lives in `src-dev/` because the enrichment skills it composes do too."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.skills.templates.core :as templates]
            ;; Force registration of the skills the graph composes.
            [digdir.skills.builtin.query-planner]
            [digdir.skills.enrichment.analyze-corpus]
            [digdir.skills.enrichment.apply-facts]
            [digdir.skills.enrichment.compose-report]
            [digdir.skills.enrichment.eval-delta]
            [digdir.skills.enrichment.extract-intent]
            [digdir.skills.enrichment.fetch-chunk-context]
            [digdir.skills.enrichment.mark-keep]
            [digdir.skills.enrichment.propose-facts]
            [digdir.skills.enrichment.revert-chunk]
            [digdir.skills.enrichment.verify-retrieval]))

;; =============================================================================
;; Inner sub-graph: enrich one chunk with fact assertions
;; =============================================================================

(def enrich-one-chunk-facts-graph
  "Per-chunk sequence: fetch chunk content, propose fact assertions,
   apply them to the fact-assertions enrichment collection, run the
   eval suite, decide keep/revert.

   Mirror of `:docs/enrich-one-chunk-phrases` with fact-flavoured
   skills + fact-aware retrieval (the eval suite picks up triples
   via the same `:enrichment-search-targets` opt-in)."
  {:id :docs/enrich-one-chunk-facts
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
     :skill :builtin/enrichment-propose-facts
     :inputs {:chunk-id :$chunk-id
              :chunk-content [:fetch :chunk-content]
              :doc-title [:fetch :doc-title]
              :doc-url [:fetch :doc-url]}}

    {:id :apply
     :skill :builtin/enrichment-apply-facts
     ;; `:doc-num` required by the Typesense schema; propose-facts
     ;; doesn't emit it, so we thread it from the fetch step.
     :inputs {:proposal :propose
              :doc-num [:fetch :doc-num]
              :collection-name :$enrichment-collection-name}}

    {:id :eval
     :skill :builtin/enrichment-eval-suite
     ;; `:dataset-config-key` hardcoded to "public-docs" — same
     ;; rationale as the phrases-graph: diagnostics falls back through
     ;; tenant-config-key when this is nil, and the literal "default"
     ;; tenant-config-key would trip the agent's allowed-dataset-scopes
     ;; check.
     :inputs {:suite-file :$suite-file
              :tenant :$tenant
              :dataset-config-key "public-docs"
              :tenant-config-key "default"
              :runtime-config-key "default"
              :agent-id "digdir.demo/self-improve-facts-graph"
              :fail-on-gate? false}}

    {:id :verify
     :skill :builtin/enrichment-verify-retrieval
     :inputs {:chunk-id :$chunk-id
              :enrichment-collection-name :$enrichment-collection-name
              :docs-collection :$docs-collection
              :chunks-collection :$chunks-collection
              :user-query :$user-query
              :eval-summary [:eval :summary]}
     :parameters {:enrichment-type :fact-assertions}}

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

(def enrich-one-chunk-facts-skill-graph
  (templates/make-skill-graph
   :docs/enrich-one-chunk-facts
   "Self-improve — enrich one chunk with fact assertions"
   "Per-chunk inner sub-graph for the fact-assertions self-improve experiment: propose triples, apply, eval, keep/revert based on gate."
   enrich-one-chunk-facts-graph
   {:version "1.0.0"
    :tags #{:demo :self-improve :enrichment :graph-only}}))

;; =============================================================================
;; Outer graph: self-improve over the whole corpus (facts)
;; =============================================================================

(def self-improve-facts-graph
  "Top-level skill graph for the fact-assertions self-improve
   experiment: intent → analyze → foreach(per-chunk) → compose-report.
   Mirror of `:docs/self-improve-phrases-graph` retargeted at
   the fact-assertions enrichment collection."
  {:id :docs/self-improve-facts-graph
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
     :parameters {:selection-mode :llm
                  :enrichment-type :fact-assertions}}

    {:id :per-chunk
     :foreach {:over [:analyze :chunk-ids]
               :as :chunk-id}
     :do {:sub-graph {:graph-id :docs/enrich-one-chunk-facts
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

(def self-improve-facts-skill-graph
  (templates/make-skill-graph
   :docs/self-improve-facts-graph
   "Self-improve fact assertions (graph variant)"
   "Phase D2 graph: same propose → apply → eval → decide structure as :docs/self-improve-phrases-graph, but enriching chunks with (subject, predicate, object) fact assertions instead of phrases."
   self-improve-facts-graph
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
;; owns docs/self-improve-facts-graph along with the other docs/* graphs.

(defn register!
  "Register both the inner sub-graph and the outer graph for the
   fact-assertions variant. Idempotent."
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
  (digdir.skills.enrichment.propose-facts/register!)
  (digdir.skills.enrichment.apply-facts/register!)
  (digdir.skills.enrichment.revert-chunk/register!)
  (digdir.skills.enrichment.verify-retrieval/register!)
  (templates/register-skill-graph! enrich-one-chunk-facts-skill-graph)
  (templates/register-skill-graph! self-improve-facts-skill-graph))

(register!)

(defn all-required-skills-present?
  "Returns true iff every skill-id referenced by the inner sub-graph
   is registered. Useful for the test suite to fail fast."
  []
  (let [referenced #{:builtin/enrichment-fetch-chunk-context
                     :builtin/enrichment-propose-facts
                     :builtin/enrichment-apply-facts
                     :builtin/enrichment-eval-suite
                     :builtin/enrichment-verify-retrieval
                     :builtin/enrichment-mark-keep
                     :builtin/enrichment-revert-chunk}]
    (every? #(some? (skills/get-skill %)) referenced)))
