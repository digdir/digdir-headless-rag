(ns digdir.demo.self-improve-graph-test
  "Coverage for the self-improve graph variant.

   Today this exercises the INNER sub-graph (`:docs/enrich-one-chunk`)
   only — the outer graph (`:docs/self-improve-graph`) lands in
   Graph.6 and will get its own tests there. Coverage here is split into:

   - Registration: the sub-graph is in the templates registry.
   - Schema: the graph passes `fully-validate-graph!` (catches typos in
     `:select` branches, missing `:do`, broken input refs, etc.).
   - Skill reachability: every skill-id the graph references is registered.

   We deliberately do NOT run the graph end-to-end here — that needs a
   live Typesense + LLM. The smoke run is the playground side-by-side
   comparison (Graph.8)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.demo.self-improve-graph :as sig]
            ;; The inner per-chunk sub-graph was promoted to src/ in
            ;; slice 1 of #82 (#89); the outer graph stays in src-dev.
            [digdir.skills.enrichment.questions-graph :as questions-graph]
            [digdir.skills.enrichment.analyze-corpus :as enrichment-analyze]
            [digdir.skills.enrichment.apply-questions :as enrichment-apply]
            [digdir.skills.enrichment.batch-decide :as enrichment-batch-decide]
            [digdir.skills.enrichment.compose-report :as enrichment-compose]
            [digdir.skills.enrichment.eval-delta :as enrichment-eval]
            [digdir.skills.enrichment.eval-sweep :as enrichment-eval-sweep]
            [digdir.skills.enrichment.extract-intent :as enrichment-intent]
            [digdir.skills.enrichment.fetch-chunk-context :as enrichment-fetch]
            [digdir.skills.enrichment.mark-keep :as enrichment-keep]
            [digdir.skills.enrichment.propose-questions :as enrichment-propose]
            [digdir.skills.enrichment.revert-chunk :as enrichment-revert]
            [digdir.skills.enrichment.verify-retrieval :as enrichment-verify]
            [digdir.skills.graph.schema :as graph-schema]
            [digdir.skills.templates.core :as templates]))

(use-fixtures :once
  (fn [t]
    ;; Re-register enrichment skills so a prior test that called
    ;; skills-api/reset-skills! doesn't leave the registry empty.
    (enrichment-analyze/register!)
    (enrichment-apply/register!)
    (enrichment-batch-decide/register!)
    (enrichment-compose/register!)
    (enrichment-eval/register!)
    (enrichment-eval-sweep/register!)
    (enrichment-intent/register!)
    (enrichment-fetch/register!)
    (enrichment-keep/register!)
    (enrichment-propose/register!)
    (enrichment-revert/register!)
    (enrichment-verify/register!)
    (sig/register!)
    (t)))

(deftest sub-graph-registered
  (testing ":docs/enrich-one-chunk is in the skill-graph registry"
    (is (some? (templates/get-skill-graph :docs/enrich-one-chunk)))))

(deftest sub-graph-passes-full-validation
  (testing "Schema + semantic validation pass — catches input-ref typos, missing branches, cycles"
    (is (some? (graph-schema/fully-validate-graph! questions-graph/enrich-one-chunk-graph))
        "fully-validate-graph! returns the graph on success, throws on failure")))

(deftest all-required-skills-are-registered
  (testing "Every skill the sub-graph dispatches to has its ns loaded and skill registered"
    (is (sig/all-required-skills-present?)
        "If this fails, one of the enrichment skill namespaces wasn't required at the top of self-improve-graph.clj — the runner will surface as a 'skill not found' deep inside a sub-graph trace, which is harder to debug.")))

(deftest sub-graph-has-no-per-chunk-decide-step
  (testing "The per-chunk :decide select step is gone — the P2 batch refactor moved the keep/revert verdict to the outer graph's :batch-decide"
    (let [step-ids (set (map :id (:steps questions-graph/enrich-one-chunk-graph)))]
      (is (not (contains? step-ids :decide))
          "Reverting per chunk required a per-chunk eval, which is the cost P2 removed")
      (is (not (contains? step-ids :eval))
          "The per-chunk eval it dispatched on is gone with it")
      (is (= #{:fetch :propose :apply :verify} step-ids)
          "Sub-graph is now propose + apply, with :verify as the cheap shadow signal"))))

(deftest sub-graph-outputs-carry-proposal-and-shadow-verify
  (testing "Declared outputs are what the outer graph's batch steps consume"
    (let [outs (set (:outputs questions-graph/enrich-one-chunk-graph))]
      (is (= #{:chunk-id :proposal :verify} outs))
      (is (not (contains? outs :decision))
          "Post-P2 the keep/revert decision is produced by :batch-decide on the outer graph, not per chunk")
      (is (contains? outs :verify)
          "D2.8 — verify is in the sub-graph outputs so compose-report can render it"))))

(deftest sub-graph-verify-step-is-the-shadow-signal
  (testing "D2.8 verify wiring, post-P2: verify pins :hypothetical-questions and runs as a standalone raw-lookup signal"
    (let [steps (:steps questions-graph/enrich-one-chunk-graph)
          verify (->> steps (filter #(= :verify (:id %))) first)]
      (is (some? verify) "An explicit :verify step exists")
      (is (= :builtin/enrichment-verify-retrieval (:skill verify)))
      (is (= :hypothetical-questions (-> verify :parameters :enrichment-type))
          "Enrichment-type is pinned as a parameter literal")
      (is (= :$user-query (-> verify :inputs :user-query))
          "User-intent topic threaded into the verify step")
      (is (nil? (-> verify :inputs :eval-summary))
          "Deliberately NOT fed an eval summary — without it verify's :improved? is the cheap lookup-only signal the report compares against the batch verdict"))))

;; =============================================================================
;; Outer graph
;; =============================================================================

(deftest outer-graph-registered
  (testing ":docs/self-improve-graph is in the skill-graph registry"
    (is (some? (templates/get-skill-graph :docs/self-improve-graph)))))

(deftest outer-graph-passes-full-validation
  (testing "Schema + semantic validation pass on the outer graph"
    (is (some? (graph-schema/fully-validate-graph! sig/self-improve-graph)))))

(deftest outer-graph-foreach-targets-analyze-output
  (testing "The foreach iterates over [:analyze :chunk-ids] and dispatches natively to the inner sub-graph"
    (let [step (->> sig/self-improve-graph :steps
                    (filter #(= :per-chunk (:id %)))
                    first)]
      (is (= [:analyze :chunk-ids] (-> step :foreach :over)))
      (is (= :chunk-id (-> step :foreach :as)))
      (is (= :docs/enrich-one-chunk
             (-> step :do :sub-graph :graph-id))
          "Native foreach-sub-graph dispatch — no wrapper skill")
      (is (= :default (:on-error step))
          "One chunk's failure shouldn't abort the whole pass"))))

(deftest outer-graph-compose-wires-analyze-and-foreach
  (testing "The compose step pulls analysis from :analyze and outcomes from the foreach's :collect-as"
    (let [step (->> sig/self-improve-graph :steps
                    (filter #(= :compose (:id %)))
                    first)]
      (is (= :builtin/enrichment-compose-report (:skill step)))
      (is (= :analyze (-> step :inputs :analysis))
          "Whole :analyze outputs map (so compose can read :analysis prose)")
      (is (= [:batch-decide :outcomes] (-> step :inputs :outcomes))
          "Post-P2 outcomes come from :batch-decide (each foreach outcome merged with its :decision and :batch-verdict), not straight off the foreach"))))

;; The two steps below are what the P2 refactor moved OUT of the per-chunk
;; sub-graph. Nothing pinned them until now, which is why the sub-graph tests
;; above were able to drift against the old per-chunk shape unnoticed.

(deftest outer-graph-batch-eval-replaces-per-chunk-eval
  (testing "One eval sweep covers every chunk the foreach enriched"
    (let [step (->> sig/self-improve-graph :steps
                    (filter #(= :batch-eval (:id %)))
                    first)]
      (is (some? step) "P2 moved the eval out of the sub-graph into a single sweep")
      (is (= :builtin/enrichment-eval-sweep (:skill step)))
      (is (= [:per-chunk :chunk-outcomes] (-> step :inputs :chunk-outcomes))
          "The sweep reads the chunk-ids the foreach enriched")
      (is (= :hypothetical-questions (-> step :parameters :enrichment-type)))
      (is (seq (-> step :parameters :regression-question-ids))
          "At least one innocent-bystander query guards against broad dilution"))))

(deftest outer-graph-batch-decide-owns-keep-or-revert
  (testing "Batch decide turns sweep verdicts into per-chunk keep/revert outcomes"
    (let [step (->> sig/self-improve-graph :steps
                    (filter #(= :batch-decide (:id %)))
                    first)]
      (is (some? step) "This is what replaced the sub-graph's per-chunk :decide")
      (is (= :builtin/enrichment-batch-decide (:skill step)))
      (is (= [:batch-eval :verdicts] (-> step :inputs :verdicts)))
      (is (= [:per-chunk :chunk-outcomes] (-> step :inputs :chunk-outcomes)))
      (is (= [:analyze :enrichment-collection-name]
             (-> step :inputs :enrichment-collection-name))
          "Revert needs the collection it applied into"))))

;; =============================================================================
;; Playground agent record
;; =============================================================================

