(ns digdir.playground.diagnostics-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.playground.diagnostics :as diagnostics]))

(deftest retrieval-filter-entries-backfills-auto-filter
  (testing "Auto filter payload is normalized when explicit retrieval-filters are absent"
    (let [entries (diagnostics/retrieval-filter-entries
                   {:auto-filter-applied {:fields [{:field "country"
                                                   :selected-options ["NO"]}]}
                    :auto-filter-fallback true})]
      (is (= 1 (count entries)))
      (is (= :auto (get-in entries [0 :type])))
      (is (= true (get-in entries [0 :fallback])))
      (is (= :search-attribution (get-in entries [0 :source])))
      (is (= "country" (get-in entries [0 :filter :fields 0 :field]))))))

(deftest format-retrieval-filter-label-renders-source-type-and-fields
  (testing "Filter label includes source, mode, fallback marker, and field values"
    (let [label (diagnostics/format-retrieval-filter-label
                 {:source :agent-trace
                  :type :auto
                  :fallback true
                  :filter {:fields [{:field "country"
                                     :selected-options ["NO" "SE"]}]}})]
      (is (= "Agent auto (fallback): country=NO,SE" label)))))

(deftest normalize-diagnostics-preserves-canonical-action-trace
  (let [trace [{:id :plan
                :skill-id :builtin/query-planner
                :kind :plan
                :status :ok
                :duration-ms 12
                :result {:queries ["q"]}}]
        normalized (diagnostics/normalize-diagnostics {:action-trace trace})]
    (is (= trace (:action-trace normalized)))))

(deftest normalize-diagnostics-builds-run-summary
  (testing "Agent-oriented diagnostics are normalized into a compact UI summary"
    (let [normalized (diagnostics/normalize-diagnostics
                      {:query-intent {:answer-type :numeric-fact
                                      :entity "Digdir"
                                      :year-or-date "2022"
                                      :metric "årsverk"
                                      :doc-family-preference "årsrapport"}
                       :budget-state {:search-passes-used 1
                                      :search-passes-remaining 1
                                      :read-operations-used 2
                                      :read-operations-remaining 3}
                       :sufficiency-decisions [{:status :insufficient
                                                :action :re-search
                                                :insufficiency {:failure-type :missing-numeric-fact
                                                                :target-metric "årsverk"
                                                                :target-year "2022"}}]
                       :search-history [{:queries ["årsverk Digdir 2022"]
                                         :result-count 4
                                         :new-count 4}]
                       :read-history [{:mode :chunk-ids
                                       :chunk-ids ["c1"]
                                       :returned-count 1
                                       :content-length 1200}]})]
      (is (= :numeric-fact (get-in normalized [:run-summary :query-intent :answer-type])))
      (is (= 1 (get-in normalized [:run-summary :budget-state :search-passes-used])))
      (is (= :missing-numeric-fact (get-in normalized [:run-summary :latest-decision :failure-type])))
      (is (= 1 (count (:search-history normalized))))
      (is (= 1 (count (:read-history normalized))))
      (is (= :chunk-ids (get-in normalized [:read-history 0 :mode]))))))

(deftest normalize-diagnostics-preserves-execution-stage-timings
  (testing "Graph and agent stage timings are compacted for Detailed diagnostics views"
    (let [normalized (diagnostics/normalize-diagnostics
                      {:execution-stage-timings [{:step-id :summarize
                                                  :skill-id :test/summarization
                                                  :stage :summarization
                                                  :duration-ms 10
                                                  :status :ok
                                                  :ignored :field}]
                       :agent-stage-timings [{:stage :agent-llm
                                              :iteration 0
                                              :duration-ms 42
                                              :status :ok
                                              :detail "{:model \"gpt\"}"
                                              :ignored :field}]})]
      (is (= [{:step-id :summarize
               :skill-id :test/summarization
               :stage :summarization
               :duration-ms 10
               :status :ok}]
             (:execution-stage-timings normalized)))
      (is (= [{:stage :agent-llm
               :iteration 0
               :duration-ms 42
               :status :ok
               :detail "{:model \"gpt\"}"}]
             (:agent-stage-timings normalized))))))

(deftest format-stage-timing-labels-and-details-are-human-readable
  (testing "Timing rows prefer friendly labels over raw stage keywords"
    (is (= "LLM turn"
           (diagnostics/format-stage-timing-label {:stage :agent-llm
                                                   :iteration 0})))
    (is (= "iteration 0 · ok"
           (diagnostics/format-stage-timing-detail {:stage :agent-llm
                                                    :iteration 0
                                                    :status :ok})))
    (is (= "Chunk read"
           (diagnostics/format-stage-timing-label {:stage :read_chunks
                                                   :tool "read_chunks"
                                                   :sub-skill :builtin/read-chunks})))))

(deftest tool-call-summaries-avoid-raw-edn
  (testing "Tool call summaries are compact and text-oriented"
    (is (= "3 query variants: a | b | c"
           (diagnostics/tool-call-input-summary
            {:tool "search_documents"
             :args {:queries ["a" "b" "c"]}})))
    (is (= "retrieve-top-k 150, max-per-document 10, query-aware-boost on"
           (diagnostics/tool-call-effective-parameters-summary
            {:tool "search_documents"
             :effective-parameters {:primary {:retrieve-top-k 150
                                              :max-per-document 10
                                              :query-aware-boost true}}})))
    (is (= "4 query variants: a | b | c ..."
           (diagnostics/tool-call-input-summary
            {:tool "search_documents"
             :args {:queries ["a" "b" "c" "d"]}})))))

(deftest normalize-diagnostics-preserves-clarification-request
  (testing "Clarification payloads are normalized into the UI-facing diagnostics shape"
    (let [normalized (diagnostics/normalize-diagnostics
                      {:status "needs_clarification"
                       :clarification-request {:question "Which Digdir report do you mean?"
                                               :options ["2022" "2023"]
                                               :context_summary "Several report years match the query."}})]
      (is (= :needs_clarification (:status normalized)))
      (is (= "Which Digdir report do you mean?"
             (get-in normalized [:clarification-request :question])))
      (is (= ["2022" "2023"]
             (get-in normalized [:clarification-request :options])))
      (is (= "Several report years match the query."
             (get-in normalized [:clarification-request :context-summary]))))))

(deftest run-summary-lines-renders-query-intent-and-budget
  (testing "Run summary lines expose the compact status fields used by the UI"
    (let [lines (diagnostics/run-summary-lines
                 {:query-intent {:answer-type :numeric-fact
                                 :entity "Digdir"
                                 :year-or-date "2022"
                                 :metric "årsverk"}
                  :budget-state {:search-passes-used 1
                                 :search-passes-remaining 1
                                 :read-operations-used 2
                                 :read-operations-remaining 2
                                 :read-content-length-used 1200
                                 :read-content-length-remaining 3800}
                  :sufficiency-decisions [{:status :conflict
                                           :action :re-search
                                           :insufficiency {:failure-type :wrong-scope}}]})]
      (is (some #(= {:label "Answer type" :value "numeric-fact"} %) lines))
      (is (some #(= {:label "Metric" :value "årsverk"} %) lines))
      (is (some #(= {:label "Search budget" :value "1/2"} %) lines))
      (is (some #(= {:label "Status" :value "conflicting"} %) lines)))))

(deftest run-summary-lines-adds-conflict-specific-fields
  (testing "Conflict runs surface distinct summary chips in the existing UI"
    (let [lines (diagnostics/run-summary-lines
                 {:budget-state {:search-passes-used 1
                                 :search-passes-remaining 1}
                  :sufficiency-decisions [{:status :conflict
                                           :action :re-search
                                           :insufficiency {:failure-type :wrong-scope
                                                           :target-metric "årsverk"
                                                           :doc-family-hint "årsrapport"}}]})]
      (is (some #(= {:label "Next action" :value "re-search"} %) lines))
      (is (some #(= {:label "Conflict metric" :value "årsverk"} %) lines))
      (is (some #(= {:label "Conflict source" :value "årsrapport"} %) lines)))))

(deftest run-summary-lines-adds-budget-mode
  (testing "Budget mode is exposed through existing summary chips"
    (let [low-lines (diagnostics/run-summary-lines
                     {:budget-state {:search-passes-used 2
                                     :search-passes-remaining 1
                                     :read-operations-used 3
                                     :read-operations-remaining 1
                                     :low-search-budget? true}})
          exhausted-lines (diagnostics/run-summary-lines
                           {:budget-state {:search-passes-used 3
                                           :search-passes-remaining 0
                                           :search-budget-exhausted? true}})]
      (is (some #(= {:label "Budget mode" :value "low"} %) low-lines))
      (is (some #(= {:label "Budget mode" :value "exhausted"} %) exhausted-lines)))))

(deftest normalize-diagnostics-preserves-read-signals-and-shadow-decisions
  (testing "Read-signal diagnostics are compacted for UI consumption"
    (let [normalized (diagnostics/normalize-diagnostics
                      {:evidence-plan {:query-intent {:answer-type :lookup}
                                       :required-claims [{:claim-id :topic-match
                                                          :text "Topic matches"
                                                          :kind :topic
                                       :critical? true}]}
                       :last-read-signal {:status :gap-remaining
                                          :scope-assessment :aligned
                                          :next-action-hint :read-more
                                          :confidence 0.71
                                          :degraded? false
                                          :evaluation-mode :llm
                                          :supported-claims [{:claim-id :topic-match
                                                              :support-level :explicit
                                                              :chunk-ids ["c1"]}]
                                          :remaining-gaps [{:claim-id :answer-bearing-evidence
                                                            :critical? true
                                                            :reason :not-yet-supported
                                                            :text "Need answer-bearing evidence"}]}
                       :shadow-sufficiency-decisions [{:status :insufficient
                                                      :action :read-more
                                                      :reason-code :critical-gaps-remaining
                                                      :missing-claims [:answer-bearing-evidence]}]})]
      (is (= :lookup (get-in normalized [:evidence-plan :query-intent :answer-type])))
      (is (= :gap-remaining (get-in normalized [:last-read-signal :status])))
      (is (= :aligned (get-in normalized [:last-read-signal :scope-assessment])))
      (is (= 0.71 (get-in normalized [:last-read-signal :confidence])))
      (is (= :llm (get-in normalized [:last-read-signal :evaluation-mode])))
      (is (= :read-more (get-in normalized [:shadow-sufficiency-decisions 0 :action])))
      (is (= :critical-gaps-remaining (get-in normalized [:shadow-sufficiency-decisions 0 :reason-code]))))))

(deftest run-summary-lines-adds-read-signal-fields
  (testing "Run summary exposes shadow routing and read-signal coverage"
    (let [lines (diagnostics/run-summary-lines
                 {:last-read-signal {:status :gap-remaining
                                     :scope-assessment :aligned
                                     :next-action-hint :read-more
                                     :confidence 0.71
                                     :degraded? false}
                  :open-evidence-gaps [{:claim-id :c2}]
                  :claim-coverage {:c1 {:claim-id :c1}}
                  :shadow-sufficiency-decisions [{:status :insufficient
                                                 :action :read-more}]})]
      (is (some #(= {:label "Shadow status" :value "insufficient"} %) lines))
      (is (some #(= {:label "Shadow action" :value "read-more"} %) lines))
      (is (some #(= {:label "Read signal" :value "gap-remaining"} %) lines))
      (is (some #(= {:label "Read scope" :value "aligned"} %) lines))
      (is (some #(= {:label "Read hint" :value "read-more"} %) lines))
      (is (some #(= {:label "Read degraded" :value "false"} %) lines))
      (is (some #(= {:label "Read confidence" :value "0.71"} %) lines))
      (is (some #(= {:label "Supported claims" :value "1"} %) lines))
      (is (some #(= {:label "Open gaps" :value "1"} %) lines)))))

(deftest run-summary-lines-adds-response-validation-fields
  (testing "Run summary exposes post-generate validation separately from read-time sufficiency"
    (let [lines (diagnostics/run-summary-lines
                 {:response-validations [{:status :insufficient
                                         :source :response-validation
                                         :action :read-more}]})]
      (is (some #(= {:label "Response validation" :value "insufficient"} %) lines))
      (is (some #(= {:label "Response action" :value "read-more"} %) lines)))))

(deftest budget-comparison-lines-summarizes-current-vs-relaxed-runs
  (testing "Comparison lines expose status and budget deltas for paired runs"
    (is (= ["Status: conflicting -> sufficient"
            "Search budget: 1/1 -> 2/2 (+1 total)"
            "Read budget: 2/2 -> 3/3 (+1 total)"
            "Read chars: 12000/12000 -> 16000/16000 (+4000 total)"]
           (diagnostics/budget-comparison-lines
            {:budget-state {:search-passes-used 1
                            :search-passes-remaining 0
                            :read-operations-used 2
                            :read-operations-remaining 0
                            :read-content-length-used 12000
                            :read-content-length-remaining 0}
             :sufficiency-decisions [{:status :conflict}]}
            {:budget-state {:search-passes-used 2
                            :search-passes-remaining 0
                            :read-operations-used 3
                            :read-operations-remaining 0
                            :read-content-length-used 16000
                            :read-content-length-remaining 0}
             :sufficiency-decisions [{:status :enough}]})))))

(deftest decision-timeline-entries-compacts-agent-loop
  (testing "Timeline entries combine search, read, and decision steps"
    (let [entries (diagnostics/decision-timeline-entries
                   {:search-history [{:queries ["årsverk Digdir 2022"]
                                     :result-count 4
                                     :new-count 3
                                     :fallback? true}]
                     :read-history [{:mode :chunk-range
                                    :doc-num 42
                                    :chunk-range [5 7]
                                    :returned-count 2
                                    :content-length 1500}]
                     :budget-state {:low-search-budget? true
                                    :low-read-budget? true}
                     :sufficiency-decisions [{:status :insufficient
                                              :action :read-more
                                              :insufficiency {:failure-type :missing-numeric-fact
                                                              :target-metric "årsverk"
                                                              :target-year "2022"
                                                             :doc-family-hint "årsrapport"}}]})]
      (is (= [{:kind :search
               :label "search 1"
               :detail "4 hits · 1 queries · 3 new · fallback · first: årsverk Digdir 2022 · budget low"}
              {:kind :read
               :label "read 1"
               :detail "chunk-range (2 chunks, 1500 chars, doc 42 5-7) · budget low"}
              {:kind :insufficient
               :label "decision 1"
               :detail "insufficient · next read-more · missing-numeric-fact · metric årsverk · year 2022 · source årsrapport"}]
             entries)))))

(deftest decision-timeline-entries-marks-exhausted-budgets
  (testing "Timeline entries highlight exhausted search and read budgets"
    (let [entries (diagnostics/decision-timeline-entries
                   {:search-history [{:queries ["årsverk Digdir 2022"]
                                     :result-count 1}]
                    :read-history [{:mode :chunk-ids
                                    :returned-count 1
                                    :content-length 500}]
                    :budget-state {:search-budget-exhausted? true
                                   :read-content-budget-exhausted? true}})]
      (is (= "1 hits · 1 queries · first: årsverk Digdir 2022 · budget exhausted"
             (get-in entries [0 :detail])))
      (is (= "chunk-ids (1 chunks, 500 chars) · read chars exhausted"
             (get-in entries [1 :detail]))))))

(deftest decision-timeline-entries-include-shadow-decisions
  (testing "Timeline entries surface shadow read-signal routing after ordinary gate decisions"
    (let [entries (diagnostics/decision-timeline-entries
                   {:shadow-sufficiency-decisions [{:status :insufficient
                                                   :action :read-more
                                                   :reason-code :critical-gaps-remaining
                                                   :missing-claims [:target-period :requested-value]}]})]
      (is (= {:kind :insufficient
              :label "shadow 1"
              :detail "shadow · next read-more · critical-gaps-remaining · missing target-period,requested-value"}
             (first entries))))))

(deftest execution-stage-timing-entries-combine-graph-and-agent-timings
  (testing "Detailed timeline exposes graph-level and agent-level execution timing entries"
    (let [entries (diagnostics/execution-stage-timing-entries
                   {:execution-stage-timings [{:step-id :summarize
                                               :skill-id :test/summarization
                                               :stage :summarization
                                               :duration-ms 10
                                               :status :ok}]
                    :agent-stage-timings [{:stage :agent-llm
                                           :iteration 0
                                           :duration-ms 12
                                           :status :ok}
                                          {:stage :search
                                           :iteration 0
                                           :tool "search_documents"
                                           :sub-skill :builtin/retrieval
                                           :duration-ms 34
                                           :status :ok
                                           :detail "{:retrieve-top-k 100}"}]})]
      (is (= [{:id [:graph 0]
               :source :graph
               :label "summarize (summarization)"
               :detail "ok"
               :duration-ms 10
               :status :ok
               :raw {:step-id :summarize
                     :skill-id :test/summarization
                     :stage :summarization
                     :duration-ms 10
                     :status :ok}}
              {:id [:agent 0]
               :source :agent
               :label "LLM turn"
               :detail "iteration 0 · ok"
               :duration-ms 12
               :status :ok
               :raw {:stage :agent-llm
                     :iteration 0
                     :duration-ms 12
                     :status :ok}}
              {:id [:agent 1]
               :source :agent
               :label "Retrieval search"
               :detail "iteration 0 · ok"
               :duration-ms 34
               :status :ok
               :raw {:stage :search
                     :iteration 0
                     :tool "search_documents"
                     :sub-skill :builtin/retrieval
                     :duration-ms 34
                     :status :ok
                     :detail "{:retrieve-top-k 100}"}}]
             entries)))))

(deftest execution-timing-summary-breaks-out-hidden-wall-clock
  (testing "Execution timing summary exposes the recorded-vs-total gap"
    (let [summary (diagnostics/execution-timing-summary
                   {:total-duration-ms 25471
                    :execution-stage-timings [{:step-id :summarize
                                               :skill-id :test/summarization
                                               :stage :summarization
                                               :duration-ms 10
                                               :status :ok}]
                    :agent-stage-timings [{:stage :agent-llm
                                           :iteration 0
                                           :duration-ms 12
                                           :status :ok}
                                          {:stage :search
                                           :iteration 0
                                           :tool "search_documents"
                                           :sub-skill :builtin/retrieval
                                           :duration-ms 34
                                           :status :ok}]})]
      (is (= 25471 (:total-ms summary)))
      (is (= 56 (:recorded-ms summary)))
      (is (= 25415 (:unexplained-ms summary)))
      (is (= 3 (:entry-count summary)))
      (is (= [:search :agent-llm :summarization]
             (map (comp :stage :raw) (:top-entries summary)))))))

(deftest agent-iteration-stage-timings-filter-to-one-iteration
  (testing "Agent trace panels can request timing entries for one specific iteration"
    (let [entries (diagnostics/agent-iteration-stage-timings
                   {:agent-stage-timings [{:stage :agent-llm
                                           :iteration 0
                                           :duration-ms 12
                                           :status :ok}
                                          {:stage :search
                                           :iteration 1
                                           :tool "search_documents"
                                           :sub-skill :builtin/retrieval
                                           :duration-ms 34
                                           :status :ok}]}
                   1)]
      (is (= [{:id [:agent 1 1]
               :source :agent
               :label "Retrieval search"
               :detail "iteration 1 · ok"
               :duration-ms 34
               :status :ok
               :raw {:stage :search
                     :iteration 1
                     :tool "search_documents"
                     :sub-skill :builtin/retrieval
                     :duration-ms 34
                     :status :ok}}]
             entries)))))

(deftest retrieved-evidence-entries-marks-read-state
  (testing "Merged retrieval candidates are annotated with read/unread state"
    (let [entries (diagnostics/retrieved-evidence-entries
                   {:merged-results [{:chunk_id "c1"
                                      :doc_num 42
                                      :chunk_index 5
                                      :content_length 700
                                      :total_chunks 12
                                      :search-types #{:content :metadata}}
                                     {:chunk_id "c2"
                                      :doc_num 42
                                      :chunk_index 6
                                      :content_length 500
                                      :total_chunks 12
                                      :search-types #{:phrase}}]
                    :read-history [{:returned-chunk-ids ["c1"]}]
                    :used-chunks [{:chunk_id "c1"}]})]
      (is (= true (get-in entries [0 :read?])))
      (is (= false (get-in entries [1 :read?])))
      (is (= ["content" "metadata"] (get-in entries [0 :search-type-labels])))
      (is (= 12 (get-in entries [0 :total_chunks]))))))

(deftest retrieved-evidence-entries-handles-agentic-search-summaries
  (testing "Agentic search summaries carry enough metadata for the evidence table"
    (let [entries (diagnostics/retrieved-evidence-entries
                   {:merged-results [{:chunk_id "c1"
                                      :doc_num "42"
                                      :chunk_index 20
                                      :content_length 356
                                      :total_chunks 30
                                      :title "Hovudtal"
                                      :metadata "Forklaring"
                                      :search-types [:content]
                                      :retrieval-boosts {:numeric-evidence 0.45
                                                         :search-type 0.35}}]
                    :read-history []
                    :used-chunks []})]
      (is (= "Hovudtal" (get-in entries [0 :title])))
      (is (= false (get-in entries [0 :read?])))
      (is (= ["content"] (get-in entries [0 :search-type-labels])))
      (is (= "numeric evidence, search type"
             (diagnostics/retrieval-explanation-text (first entries)))))))

(deftest retrieval-explanation-text-orders-strongest-signal-types
  (testing "Retrieval explanation text surfaces positive boost categories in priority order"
    (let [chunk {:retrieval-boosts {:title 0.03
                                    :year 0.15
                                    :org 0.0
                                    :search-type 0.35
                                    :content-overlap 0.2
                                    :numeric-evidence 0.45}}]
      (is (= ["numeric evidence"
              "content overlap"
              "search type"
              "year match"
              "title match"]
             (diagnostics/retrieval-explanation-labels chunk)))
      (is (= "numeric evidence, content overlap, search type, year match, title match"
             (diagnostics/retrieval-explanation-text chunk))))))

(deftest format-search-history-entry-renders-fallback-and-new-count
  (testing "Search history labels expose query batch details compactly"
    (is (= "Search: årsverk Digdir 2022 (4 hits, 2 new, fallback)"
           (diagnostics/format-search-history-entry
            {:queries ["årsverk Digdir 2022"]
             :result-count 4
             :new-count 2
             :fallback? true})))))

(deftest format-read-history-entry-renders-mode-and-target
  (testing "Read history labels distinguish chunk-id and doc-range reads"
    (is (= "Read: chunk-ids (1 chunks, 1200 chars, 2 requested chunks)"
           (diagnostics/format-read-history-entry
            {:mode :chunk-ids
             :chunk-ids ["c1" "c2"]
             :returned-count 1
             :content-length 1200})))
    (is (= "Read: chunk-range (3 chunks, 900 chars, doc 42 5-7)"
           (diagnostics/format-read-history-entry
            {:mode :chunk-range
             :doc-num 42
             :chunk-range [5 7]
             :returned-count 3
             :content-length 900})))))

(deftest format-read-history-entry-renders-skimmed-reads
  (testing "Skimmed reads are labeled distinctly from full reads"
    (is (= "Skimmed: chunk-ids (2 chunks, 500 chars, 3 requested chunks)"
           (diagnostics/format-read-history-entry
            {:mode :chunk-ids
             :chunk-ids ["c1" "c2" "c3"]
             :returned-count 2
             :content-length 500
             :max-content-length 200})))
    (is (= "Skimmed: doc-range (4 chunks, 900 chars, doc 42 5-8)"
           (diagnostics/format-read-timeline-detail
            {:mode :doc-range
             :doc-num 42
             :chunk-range {:from 5 :to 8}
             :returned-count 4
             :content-length 900
             :max-content-length 200})))))

(deftest format-decision-timeline-detail-renders-structured-insufficiency
  (testing "Decision timeline detail exposes failure reason and next action"
    (is (= "conflicting · via llm-sufficiency-gate · next re-search · wrong-scope · metric årsverk · year 2022 · source årsrapport"
           (diagnostics/format-decision-timeline-detail
            {:status :conflict
             :source :llm-sufficiency-gate
             :action :re-search
             :insufficiency {:failure-type :wrong-scope
                             :target-metric "årsverk"
                             :target-year "2022"
                             :doc-family-hint "årsrapport"}})))))

(deftest decision-timeline-entries-distinguish-shadow-and-gate-sources
  (testing "Decision timeline labels and details expose decision source"
    (is (= [{:kind :sufficient
             :label "shadow 1"
             :detail "sufficient · via shadow-read-signals · next finalize"}
            {:kind :insufficient
             :label "gate 2"
             :detail "insufficient · via llm-sufficiency-gate · next read-more · missing-date"}]
           (diagnostics/decision-timeline-entries
            {:sufficiency-decisions [{:status :sufficient
                                      :source :shadow-read-signals
                                      :action :finalize}
                                     {:status :insufficient
                                      :source :llm-sufficiency-gate
                                      :action :read-more
                                      :insufficiency {:failure-type :missing-date}}]})))))

(deftest suggested-search-queries-extracts-query-batch-from-decision-message
  (testing "Suggested next search queries are extracted from the latest re-search decision"
    (is (= ["utførte årsverk digdir 2022"
            "digdir årsrapport 2022 årsverk"]
           (diagnostics/suggested-search-queries
            {:sufficiency-decisions [{:status :insufficient
                                      :action :re-search
                                      :message (str "[SYSTEM: Current evidence is insufficient. "
                                                    "Suggested next call: search "
                                                    "{\"queries\":[\"utførte årsverk digdir 2022\","
                                                    "\"digdir årsrapport 2022 årsverk\"]}. "
                                                    "Read new hits before generating again.]")}]})))))

(deftest conflict-summary-compacts-latest-conflict-state
  (testing "Conflict summary exposes reason, target fields, and recommended action"
    (is (= {:headline "Conflicting evidence"
            :reason "wrong-scope"
            :metric "årsverk"
            :year "2022"
            :source "årsrapport"
            :action :re-search
            :gap-summary "Two plausible scopes remain."}
           (diagnostics/conflict-summary
            {:sufficiency-decisions [{:status :conflict
                                      :action :re-search
                                      :insufficiency {:failure-type :wrong-scope
                                                      :target-metric "årsverk"
                                                      :target-year "2022"
                                                      :doc-family-hint "årsrapport"}}]
             :last-insufficiency {:failure-type :wrong-scope
                                  :target-metric "årsverk"
                                  :target-year "2022"
                                  :doc-family-hint "årsrapport"
                                  :recommended-action :re-search
                                  :evidence-gap-summary "Two plausible scopes remain."}})))))

(deftest conflict-summary-lines-renders-compact-ui-lines
  (testing "Conflict lines are ready for simple inline rendering"
    (is (= ["Reason: wrong-scope"
            "Recommended action: re-search"
            "Target metric: årsverk"
            "Target year: 2022"
            "Preferred source: årsrapport"
            "Two plausible scopes remain."]
           (diagnostics/conflict-summary-lines
            {:sufficiency-decisions [{:status :conflict
                                      :action :re-search
                                      :insufficiency {:failure-type :wrong-scope
                                                      :target-metric "årsverk"
                                                      :target-year "2022"
                                                      :doc-family-hint "årsrapport"}}]
             :last-insufficiency {:failure-type :wrong-scope
                                  :target-metric "årsverk"
                                  :target-year "2022"
                                  :doc-family-hint "årsrapport"
                                  :recommended-action :re-search
                                  :evidence-gap-summary "Two plausible scopes remain."}})))))

(deftest conflict-summary-falls-back-to-response-validation-insufficiency
  (testing "Post-generate conflicts still render a useful summary when read-time insufficiency stayed nil"
    (is (= {:headline "Conflicting evidence"
            :reason "conflict"
            :metric "årsverk"
            :year "2022"
            :source "årsrapport"
            :action :finalize
            :gap-summary "Svarutkastet blander avtalte og utførte årsverk."}
           (diagnostics/conflict-summary
            {:response-validations [{:status :conflict
                                     :source :response-validation
                                     :action :finalize}]
             :last-response-validation-insufficiency
             {:failure-type :conflict
              :target-metric "årsverk"
              :target-year "2022"
              :doc-family-hint "årsrapport"
              :recommended-action :finalize
              :evidence-gap-summary "Svarutkastet blander avtalte og utførte årsverk."}})))))

(deftest agent-loop-text-renders-compact-debug-block
  (testing "Agent loop text summarizes summary, timeline, search, and read state"
    (let [text (diagnostics/agent-loop-text
                {:query-intent {:answer-type :numeric-fact
                                :metric "årsverk"}
                 :budget-state {:search-passes-used 1
                                :search-passes-remaining 1}
                 :sufficiency-decisions [{:status :insufficient
                                          :action :re-search
                                          :insufficiency {:failure-type :missing-numeric-fact}
                                          :message (str "[SYSTEM: Current evidence is insufficient. "
                                                        "Suggested next call: search "
                                                        "{\"queries\":[\"utførte årsverk digdir 2022\"]}. "
                                                        "Read new hits before generating again.]")}]
                 :search-history [{:queries ["årsverk Digdir 2022"]
                                   :result-count 4}]
                 :read-history [{:mode :chunk-ids
                                 :returned-count 1
                                 :content-length 1200}]})]
      (is (string? text))
      (is (str/includes? text "Answer type: numeric-fact"))
      (is (str/includes? text "Timeline:"))
      (is (str/includes? text "- search"))
      (is (str/includes? text "- Search: årsverk Digdir 2022 (4 hits)"))
      (is (str/includes? text "- Read: chunk-ids (1 chunks, 1200 chars)"))
      (is (str/includes? text "Suggested next search:"))
      (is (str/includes? text "- utførte årsverk digdir 2022")))))

(deftest normalize-diagnostics-compacts-iteration-history
  (testing "Agentic tool results are summarized during normalization"
    (let [normalized (diagnostics/normalize-diagnostics
                      {:agent-trace [{:iteration 0
                                      :reasoning "Thinking..."
                                      :tool-calls [{:tool "search_documents"
                                                    :args {:queries ["q1"]}
                                                    :result-summary "returned 5 hits"}]}]
                       :iteration-history [{:iteration 0
                                            :tool-calls [{:tool "search_documents"
                                                          :args {:queries ["q1"]}
                                                          :result-summary "returned 5 hits"}
                                                         {:tool "read_chunks"
                                                          :args {:chunk_ids ["c1" "c2"]
                                                                 :max_content_length 500}
                                                          :result-summary "Read 800 chars"}]
                                            :budget-snapshot {:search-passes-used 1
                                                              :search-passes-remaining 3
                                                              :read-operations-used 1
                                                              :read-operations-remaining 4
                                                              :read-content-length-used 800
                                                              :read-content-length-remaining 11200}}]})]
      (is (= "Searched 1 phrasings; found 5 hits."
             (get-in normalized [:iteration-history 0 :tool-calls 0 :summary])))
      (is (= "Skimmed 2 specific chunks (800 chars)."
             (get-in normalized [:iteration-history 0 :tool-calls 1 :summary])))
      (is (= "Skimmed 2 chunks from the search results"
             (get-in normalized [:iteration-history 0 :tool-calls 1 :narrative])))
      (is (= :skim
             (get-in normalized [:iteration-history 0 :tool-calls 1 :operation-kind])))
      (is (= 1
             (get-in normalized [:iteration-history 0 :budget-snapshot :search-passes-used]))))))

(deftest budget-snapshot-items-splits-search-read-and-char-usage
  (testing "Per-turn budget snapshots become simple UI items"
    (is (= [{:key :search
             :label "Search"
             :used 2
             :total 4
             :status :low}
            {:key :reads
             :label "Reads"
             :used 3
             :total 5
             :status :low}
            {:key :chars
             :label "Chars"
             :used 1200
             :total 5000
             :status :low}]
           (diagnostics/budget-snapshot-items
            {:search-passes-used 2
             :search-passes-remaining 2
             :read-operations-used 3
             :read-operations-remaining 2
             :read-content-length-used 1200
             :read-content-length-remaining 3800
             :low-search-budget? true
             :low-read-budget? true})))))

(deftest run-summary-includes-counts-and-duration
  (testing "Run summary exposes counts and timing metadata"
    (let [summary (diagnostics/run-summary
                   {:search-history [{:queries ["q1"]}]
                    :read-history [{:mode :chunk-ids} {:mode :chunk-range}]
                    :backend-issues [{:message "error"}]
                    :total-duration-ms 1500})]
      (is (= 1 (:search-passes summary)))
      (is (= 2 (:read-operations summary)))
      (is (= 1 (:backend-issue-count summary))))))
