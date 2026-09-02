(ns digdir.demo.self-improve-agent-test
  "Phase C unit tests — verify each ReAct tool's execute-fn shapes
   inputs correctly, dispatches to the right underlying skill, and
   returns LLM-readable JSON.

   We stub `skills/execute-skill` and the Typesense client so tests
   run in milliseconds and tell us about the wiring, not the model.
   End-to-end agent runs are deferred to the live smoke."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.demo.self-improve-agent :as sia]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.enrichment.collections :as enrich-coll]
            [typesense.client :as ts]))

(def ^:private ambient-ctx-fixture
  "A representative ambient-ctx as the agent's tool dispatch would
   build for a dataset-bound call."
  {:opts {:tenant "digdir"
          :dataset-config-key "public-docs"
          :tenant-config-key "default"
          :pipeline-config {:pipeline-name "test-pipeline"
                            :source-type :website
                            :chunk-strategy :fixed
                            :chunk-minimum-length 200
                            :chunk-maximum-length 1000}
          :docs-collection "test_documents_abc"
          :chunks-collection "test_chunks_abc"}
   :dataset-ref {:tenant "digdir" :dataset-config-key "public-docs"}
   :docs-collection "test_documents_abc"
   :chunks-collection "test_chunks_abc"})

(defn- parse-tool-output
  "Tool execute-fns return JSON strings; tests need maps."
  [s]
  (json/read-str s :key-fn keyword))

(use-fixtures :each
  (fn [t]
    ;; Always provide a fake Typesense settings so the tools don't
    ;; throw on `(when-not settings ...)`. Tests redef the actual
    ;; ts/search calls per-test as needed.
    (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "stub" :key "k"})]
      (t))))

;; ============================================================================
;; analyze_corpus
;; ============================================================================

(deftest derive-enrichment-collection-name-from-docs-name
  (testing "Phase B.1's naming invariant lets us derive the enrichment name from docs name alone"
    (let [derive #'sia/derive-enrichment-collection-name]
      (is (= "website_enrichment_hypothetical_questions_ab897fbdedfa"
             (derive "website_documents_ab897fbdedfa")))
      (is (= "pipeline_enrichment_hypothetical_questions_a1b2c3d4e5f6"
             (derive "pipeline_documents_a1b2c3d4e5f6")))
      (is (nil? (derive nil))
          "Nil input returns nil — callers can detect missing pipeline")
      (is (nil? (derive ""))
          "Blank input returns nil")
      (is (nil? (derive "no_documents_segment"))
          "Names that don't end in {documents_}<hex> return nil — be strict to avoid silently joining to the wrong collection")
      (is (nil? (derive "website_documents_not-hex-but-words"))
          "Hash segment must be hex digits only"))))

(deftest analyze-corpus-resolves-enrichment-collection-from-docs-name
  (testing "When pipeline-config isn't in ambient-ctx, the enrichment collection name is still derived"
    (with-redefs [ts/search
                  (fn [_settings coll-name _opts]
                    (cond
                      (= coll-name "test_documents_abc") {:found 1 :hits []}
                      (= coll-name "test_chunks_abc")
                      {:found 100
                       :hits [{:document {:chunk_id "c1" :doc_num "d1"
                                          :chunk_index 0 :content_length 100}}]}
                      ;; enrichment collection found via the new derive path
                      (= coll-name "test_enrichment_hypothetical_questions_abc")
                      {:found 4 :hits []}
                      :else {:found 0 :hits []}))]
      (let [ambient-without-pipeline-config
            (-> ambient-ctx-fixture
                (assoc :docs-collection "test_documents_abc"
                       :chunks-collection "test_chunks_abc")
                (update :opts dissoc :pipeline-config))
            out (sia/execute-analyze-corpus-tool
                 {} nil ambient-without-pipeline-config)
            m (parse-tool-output out)]
        (is (= "test_enrichment_hypothetical_questions_abc"
               (:enrichment-collection m))
            "analyze_corpus surfaces the enrichment collection even without pipeline-config")
        (is (= 4 (:existing-enrichment-rows m))
            "And counts existing rows in that collection")))))

(deftest analyze-corpus-shapes-stats-and-sample
  (testing "Stats + sample come back in the JSON envelope"
    (with-redefs [ts/search
                  (fn [_settings coll-name _opts]
                    (cond
                      ;; docs-collection: just the count
                      (= coll-name "test_documents_abc")
                      {:found 42 :hits []}
                      ;; chunks-collection: count + sample rows
                      (= coll-name "test_chunks_abc")
                      {:found 1234
                       :hits [{:document {:chunk_id "c1" :doc_num "d1"
                                          :chunk_index 0 :content_length 500
                                          :title "About"}}
                              {:document {:chunk_id "c2" :doc_num "d1"
                                          :chunk_index 1 :content_length 700
                                          :title "About"}}]}
                      ;; enrichment-collection: 0 rows so far
                      :else
                      {:found 0 :hits []}))]
      (let [out (sia/execute-analyze-corpus-tool
                 {:sample_size 2}
                 nil
                 ambient-ctx-fixture)
            m (parse-tool-output out)]
        (is (= "analyze_corpus" (:tool m)))
        (is (= 42 (:total-docs m)))
        (is (= 1234 (:total-chunks m)))
        (is (= "test_documents_abc" (:docs-collection m)))
        (is (= 2 (count (:sample-chunks m))))
        (is (= ["c1" "c2"] (map :chunk_id (:sample-chunks m))))))))

(deftest analyze-corpus-error-comes-back-as-json
  (testing "Throws inside the tool body get rendered as JSON :error"
    (with-redefs [ts/search (fn [& _] (throw (ex-info "ts down" {:code 503})))]
      (let [out (sia/execute-analyze-corpus-tool
                 {} nil ambient-ctx-fixture)
            m (parse-tool-output out)]
        (is (= "analyze_corpus" (:tool m)))
        (is (re-find #"ts down" (:error m)))))))

;; ============================================================================
;; propose_questions_for_chunk
;; ============================================================================

(deftest propose-questions-dispatches-to-skill-and-passes-content
  (testing "Tool fetches the chunk, then calls the propose-questions skill with that content"
    (let [captured-inputs (atom nil)]
      (with-redefs [ts/search
                    (fn [_settings coll-name opts]
                      (cond
                        (and (= coll-name "test_chunks_abc")
                             (re-find #"chunk_id:=8e22" (:filter_by opts)))
                        {:hits [{:document {:chunk_id "8e22ae4b88b1"
                                            :doc_num "6bfb44e9124f"
                                            :content_markdown "Altinn 3 ble lansert i juni 2020."
                                            :url "https://example.no/about"}}]}
                        ;; doc-title lookup
                        (and (= coll-name "test_documents_abc")
                             (re-find #"doc_num:=6bfb" (:filter_by opts)))
                        {:hits [{:document {:title "About"}}]}
                        :else {:hits []}))
                    skills/execute-skill
                    (fn [skill-id ctx]
                      (reset! captured-inputs (assoc (:inputs ctx) :skill-id skill-id))
                      (skills/success-result
                       {:chunk-id (:chunk-id (:inputs ctx))
                        :questions ["Q1 about Altinn 3" "Q2 about Altinn 3"
                                    "Q3 about Altinn 3" "Q4 about Altinn 3"]
                        :provenance {:model "gpt-stub" :prompt-hash "abc"
                                     :generated-at-ms 1700000000000
                                     :question-count 4}}
                       {}))]
        (let [out (sia/execute-propose-questions-tool
                   {:chunk_id "8e22ae4b88b1" :question_count 4}
                   nil
                   ambient-ctx-fixture)
              m (parse-tool-output out)]
          (is (= "propose_questions_for_chunk" (:tool m)))
          (is (= "8e22ae4b88b1" (:chunk_id m)))
          (is (= 4 (count (:questions m))))
          (is (= "About" (:doc_title m)))
          ;; Skill was called with the right shape
          (is (= :builtin/enrichment-propose-questions (:skill-id @captured-inputs)))
          (is (= "Altinn 3 ble lansert i juni 2020." (:chunk-content @captured-inputs)))
          (is (= "About" (:doc-title @captured-inputs))))))))

(deftest propose-questions-handles-missing-chunk
  (testing "Unknown chunk_id surfaces a clear error, doesn't invoke the skill"
    (let [skill-called (atom false)]
      (with-redefs [ts/search (fn [& _] {:hits []})
                    skills/execute-skill (fn [& _] (reset! skill-called true))]
        (let [out (sia/execute-propose-questions-tool
                   {:chunk_id "no-such"} nil ambient-ctx-fixture)
              m (parse-tool-output out)]
          (is (false? @skill-called))
          (is (re-find #"not found" (:error m))))))))

;; ============================================================================
;; apply_enrichments
;; ============================================================================

(deftest apply-enrichments-normalizes-json-arg-keys
  (testing "LLM-supplied string-keyed proposals get normalized to kebab-keyword shape"
    (let [captured-inputs (atom nil)]
      (with-redefs [skills/execute-skill
                    (fn [skill-id ctx]
                      (reset! captured-inputs (assoc (:inputs ctx) :skill-id skill-id))
                      (skills/success-result
                       {:applied-count 4
                        :chunk-ids ["c1"]
                        :collection-name "stub_enrichment"} {}))
                    enrich-coll/ensure-collection!
                    (fn [& _] :ok)]
        (let [llm-style-args
              {:proposals
               [{"chunk_id" "c1"
                 "doc_num" "d1"
                 "questions" ["q1" "q2" "q3" "q4"]
                 "provenance" {"model" "gpt-stub"
                               "prompt_hash" "abc"
                               "generated_at_ms" 1700000000000}}]}
              out (sia/execute-apply-enrichments-tool
                   llm-style-args nil ambient-ctx-fixture)
              m (parse-tool-output out)]
          (is (= "apply_enrichments" (:tool m)))
          (is (= 4 (:applied-count m)))
          ;; And the skill saw kebab-keyword keys + correctly mapped provenance
          (let [skill-proposals (:proposals @captured-inputs)
                p (first skill-proposals)]
            (is (= "c1" (:chunk-id p)))
            (is (= "d1" (:doc-num p)))
            (is (= 4 (count (:questions p))))
            (is (= "gpt-stub" (-> p :provenance :model)))
            (is (= "abc" (-> p :provenance :prompt-hash)))
            (is (= 1700000000000 (-> p :provenance :generated-at-ms)))))))))

(deftest apply-enrichments-rejects-empty-batch
  (testing "Empty :proposals returns a clear error, doesn't call the skill"
    (let [skill-called (atom false)]
      (with-redefs [skills/execute-skill (fn [& _] (reset! skill-called true))
                    enrich-coll/ensure-collection! (fn [& _] :ok)]
        (let [out (sia/execute-apply-enrichments-tool
                   {:proposals []} nil ambient-ctx-fixture)
              m (parse-tool-output out)]
          (is (false? @skill-called))
          (is (re-find #"No proposals" (:error m))))))))

(deftest apply-enrichments-passes-dry-run-through
  (testing ":dry_run flag from the LLM args reaches the skill"
    (let [captured-params (atom nil)]
      (with-redefs [skills/execute-skill
                    (fn [_skill-id ctx]
                      (reset! captured-params (:parameters ctx))
                      (skills/success-result
                       {:applied-count 0 :chunk-ids [] :collection-name "x"} {}))
                    enrich-coll/ensure-collection! (fn [& _] :ok)]
        (sia/execute-apply-enrichments-tool
         {:proposals [{:chunk_id "c1" :questions ["q1"]}]
          :dry_run true}
         nil
         ambient-ctx-fixture)
        (is (true? (:dry-run? @captured-params)))))))

;; ============================================================================
;; run_eval_delta
;; ============================================================================

(deftest run-eval-delta-dispatches-with-correct-inputs
  (testing "Tool forwards suite, tenant, dataset to the eval-suite skill"
    (let [captured-inputs (atom nil)]
      (with-redefs [skills/execute-skill
                    (fn [skill-id ctx]
                      (reset! captured-inputs (assoc (:inputs ctx) :skill-id skill-id))
                      (skills/success-result
                       {:summary {:cases 1 :current-pass 1 :relaxed-pass 0
                                  :gate-pass false :error-count 0}}
                       {}))]
        (let [out (sia/execute-run-eval-delta-tool
                   {:suite_file "test/fixtures/agent/altinn3_lansert_stability.edn"
                    :enrichment_search_targets true
                    :graph_variant "bundled"}
                   nil
                   ambient-ctx-fixture)
              m (parse-tool-output out)]
          (is (= "run_eval_delta" (:tool m)))
          (is (true? (:enrichment-active? m)))
          (is (= "test/fixtures/agent/altinn3_lansert_stability.edn" (:suite m)))
          (is (= 1 (-> m :summary :current-pass)))
          (is (= :builtin/enrichment-eval-suite (:skill-id @captured-inputs)))
          (is (= "digdir" (:tenant @captured-inputs)))
          (is (= "public-docs" (:dataset-config-key @captured-inputs)))
          (is (= :bundled (:graph-variant @captured-inputs)))
          (is (re-find #":enrichment-search-targets" (:retrieval-params @captured-inputs))))))))

(deftest run-eval-delta-baseline-omits-enrichment-param
  (testing "When :enrichment_search_targets is false, retrieval-params is absent"
    (let [captured-inputs (atom nil)]
      (with-redefs [skills/execute-skill
                    (fn [_skill-id ctx]
                      (reset! captured-inputs (:inputs ctx))
                      (skills/success-result
                       {:summary {:cases 1 :current-pass 0 :relaxed-pass 0
                                  :gate-pass false :error-count 0}}
                       {}))]
        (sia/execute-run-eval-delta-tool
         {:enrichment_search_targets false}
         nil
         ambient-ctx-fixture)
        (is (not (contains? @captured-inputs :retrieval-params))
            "Baseline runs must not carry the enrichment override")))))

;; ============================================================================
;; Agent registration + definition sanity
;; ============================================================================

