(ns digdir.playground.ui.observability.live-next-test
  "Unit coverage for the enrichment-tool view-model wiring added to
   live-next. The Electric `e/defn` UI components themselves are
   visually verified in the playground (and depend on the Hyperfiddle
   client runtime), so we don't try to exercise them here. What we DO
   test:

     - event-kind maps each self-improve tool name to the right kind
     - parse-tool-json round-trips the JSON the tools emit
     - tool-call->event attaches :parsed-enrichment for those kinds
       only, with the right shape, and doesn't mangle other kinds

   These are the integration points where a typo would silently break
   the drawer panel for a single tool — exactly the kind of regression
   that a passing playground demo wouldn't catch."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.playground.action-trace :as action-trace]
            [digdir.playground.ui.observability.live-next :as live-next]))

(def ^:private event-kind #'live-next/event-kind)
(def ^:private tool-call->event #'live-next/tool-call->event)
(def ^:private parse-tool-json #'live-next/parse-tool-json)
(def ^:private stage-friendly-label #'live-next/stage-friendly-label)

(deftest event-kind-maps-self-improve-tools
  (testing "Each self-improve-agent tool name resolves to its own :kind"
    (is (= :enrichment-analyze (event-kind nil "analyze_corpus")))
    (is (= :enrichment-propose (event-kind nil "propose_questions_for_chunk")))
    (is (= :enrichment-apply (event-kind nil "apply_enrichments")))
    (is (= :enrichment-eval (event-kind nil "run_eval_delta"))))
  (testing "Unknown tools still fall through to :tool"
    (is (= :tool (event-kind nil "something_unknown"))))
  (testing "Standard agent tools still map to their existing kinds"
    (is (= :search (event-kind nil "search_documents")))
    (is (= :read (event-kind nil "read_chunks")))
    (is (= :synthesis (event-kind nil "generate_response")))))

(deftest parse-tool-json-handles-shapes
  (testing "Round-trips a typical apply_enrichments result-summary"
    (let [raw (json/write-str {:tool "apply_enrichments"
                               :applied-count 4
                               :chunk_ids ["a" "b"]
                               :dry_run false})
          parsed (parse-tool-json raw)]
      (is (= "apply_enrichments" (:tool parsed)))
      (is (= 4 (:applied-count parsed)))
      (is (= ["a" "b"] (:chunk_ids parsed)))
      (is (false? (:dry_run parsed)))))
  (testing "Returns nil on nil / non-string / malformed input"
    (is (nil? (parse-tool-json nil)))
    (is (nil? (parse-tool-json "not json")))
    (is (nil? (parse-tool-json 42)))))

(deftest tool-call->event-attaches-parsed-enrichment-only-for-enrichment-kinds
  (let [tc-apply {:tool "apply_enrichments"
                  :result-summary (json/write-str {:tool "apply_enrichments"
                                                   :collection-name "enrich_hq_abc"
                                                   :applied-count 3
                                                   :chunk_ids ["c1" "c2" "c3"]
                                                   :dry_run false})
                  :ok? true
                  :duration-ms 42}
        tc-search {:tool "search_documents"
                   :result-summary "Search pass 1: found 7 chunks (7 new)."
                   :ok? true
                   :duration-ms 100}
        ev-apply (tool-call->event tc-apply {} {})
        ev-search (tool-call->event tc-search {} {})]
    (testing ":enrichment-apply event carries the parsed structured map"
      (is (= :enrichment-apply (:kind ev-apply)))
      (is (= "Apply enrichments" (:label ev-apply)))
      (is (= "enrich_hq_abc" (-> ev-apply :parsed-enrichment :collection-name)))
      (is (= 3 (-> ev-apply :parsed-enrichment :applied-count)))
      (is (= ["c1" "c2" "c3"] (-> ev-apply :parsed-enrichment :chunk_ids))))
    (testing "Non-enrichment events are NOT given :parsed-enrichment"
      (is (= :search (:kind ev-search)))
      (is (false? (contains? ev-search :parsed-enrichment))))))

(deftest tool-call->event-attaches-eval-summary
  (testing "Eval suite result-summary parses into :summary {:gate-pass ...}"
    (let [tc {:tool "run_eval_delta"
              :result-summary (json/write-str
                               {:tool "run_eval_delta"
                                :suite "test/fixtures/agent/altinn3_lansert_stability.edn"
                                :enrichment-active? true
                                :summary {:gate-pass true
                                          :cases 1
                                          :current-pass 0
                                          :relaxed-pass 1
                                          :improved 1
                                          :regressed 0
                                          :error-count 0}})
              :ok? true
              :duration-ms 129000}
          ev (tool-call->event tc {} {})
          s (-> ev :parsed-enrichment :summary)]
      (is (= :enrichment-eval (:kind ev)))
      (is (= "Eval delta" (:label ev)))
      (is (true? (:gate-pass s)))
      (is (= 1 (:improved s)))
      (is (= 0 (:regressed s))))))

(deftest tool-call->event-handles-malformed-summary-gracefully
  (testing "If result-summary isn't valid JSON, :parsed-enrichment is absent"
    (let [tc {:tool "apply_enrichments"
              :result-summary "not json at all"
              :ok? true
              :duration-ms 1}
          ev (tool-call->event tc {} {})]
      (is (= :enrichment-apply (:kind ev)))
      (is (false? (contains? ev :parsed-enrichment))))))

(deftest orchestration-stage-labels-explain-user-visible-work
  (is (= "Prepare agent" (stage-friendly-label nil "agent-setup")))
  (is (= "Reason and choose tools"
         (stage-friendly-label nil "agent-bundle-llm-and-tools")))
  (is (= "Evaluate evidence"
         (stage-friendly-label nil "agent-bundle-evaluate-evidence")))
  (is (= "Check evidence sufficiency"
         (stage-friendly-label nil "agent-bundle-sufficiency-gates")))
  (is (= "Choose the next step"
         (stage-friendly-label nil "agent-bundle-record-and-route"))))

(deftest orchestration-events-hide-json-behind-readable-summaries
  (let [event (tool-call->event
               {:tool "agent-bundle-llm-and-tools"
                :result-summary (json/write-str
                                 {:workspace-after-tools {:context-docs []}
                                  :evidence-plan {:query "Altinn"}})
                :ok? true}
               {}
               {})]
    (is (= "Reasoning completed and the next action was selected."
           (:summary event)))
    (is (str/starts-with? (:full-summary event) "{"))
    (is (= "⚙" (live-next/event-kind-icon (:kind event))))))

(deftest action-kinds-have-compact-visual-markers
  (is (= "✦" (live-next/event-kind-icon :llm)))
  (is (= "⌕" (live-next/event-kind-icon :search)))
  (is (= "▤" (live-next/event-kind-icon :read)))
  (is (= "✎" (live-next/event-kind-icon :synthesis))))

(deftest graph-stages-have-semantic-kinds-and-localized-labels
  (testing "Overview stages no longer fall through to a generic gear and raw id"
    (is (= :plan (event-kind :query-planner "query-planner")))
    (is (= :search (event-kind :retrieval "retrieval")))
    (is (= :rerank (event-kind :rerank "rerank")))
    (is (= :sufficiency
           (event-kind :overview-evidence-gate "overview-evidence-gate")))
    (is (= :synthesis
           (event-kind :overview-synthesis "overview-synthesis")))
    (is (= :finalize
           (event-kind :overview-finalize "overview-finalize"))))
  (testing "The view-model locale is explicit and survives async rendering"
    (is (= "Søkeplanlegging"
           (stage-friendly-label :nb :query-planner "query-planner")))
    (is (= "Dokumentsøk"
           (stage-friendly-label :nb :retrieval "retrieval")))
    (is (= "Kontroller dokumentasjon"
           (stage-friendly-label :nb :overview-evidence-gate
                                 "overview-evidence-gate")))
    (is (= "Ferdigstill svar"
           (stage-friendly-label :nb :overview-finalize "overview-finalize")))))

(deftest completed-graph-timings-produce-action-tabs-in-a-collapsed-shell
  (let [diagnostics
        {:status :complete
         ;; Non-agent graph runs persist these empty collections. They must not
         ;; mask the populated execution timings below.
         :agent-trace []
         :agent-stage-timings []
         :execution-stage-timings
         [{:stage :query-planner :status :ok :duration-ms 6500}
          {:stage :retrieval :status :ok :duration-ms 1700}
          {:stage :rerank :status :ok :duration-ms 530}
          {:stage :overview-evidence-gate :status :ok :duration-ms 17}
          {:stage :overview-synthesis :status :ok :duration-ms 6400}
          {:stage :overview-finalize :status :ok :duration-ms 32}]}
        vm (live-next/diagnostics->view-model :nb diagnostics)
        actions (:actions vm)]
    (is (= :nb (:language vm)))
    (is (empty? (:iterations vm)))
    (is (= 6 (count actions)))
    (is (= [:plan :search :rerank :sufficiency :synthesis :finalize]
           (mapv :kind actions)))
    (is (= ["Søkeplanlegging"
            "Dokumentsøk"
            "Reranger resultater"
            "Kontroller dokumentasjon"
            "Skriv svar"
           "Ferdigstill svar"]
           (mapv :label actions)))
    (is (every? :legacy? actions))
    (is (false? (live-next/default-shell-expanded? vm)))
    (is (true? (live-next/default-shell-expanded? {:status :running})))))

(deftest completed-graph-action-tabs-derive-semantic-content
  (let [trace [(action-trace/progress->action
                {:event :step/completed :step-id :plan
                 :skill-id :builtin/query-planner :duration-ms 10
                 :outputs {:queries ["primary query" "broader query"]}})
               (action-trace/progress->action
                {:event :step/completed :step-id :retrieve
                 :skill-id :builtin/retrieval :duration-ms 20
                 :outputs {:search-attribution {:merged 10 :phrase 4 :content 7 :metadata 2}}})
               (action-trace/progress->action
                {:event :step/completed :step-id :rerank
                 :skill-id :builtin/rerank :duration-ms 30
                 :outputs {:chunks [{:chunk_id "c1" :doc_num "d1"}
                                    {:chunk_id "c2" :doc_num "d2"}]}})
               (action-trace/progress->action
                {:event :step/completed :step-id :gate
                 :skill-id :builtin/overview-evidence-gate :duration-ms 5
                 :outputs {:evidence-sufficient? true
                           :evidence-summary {:context-doc-count 2
                                              :distinct-document-count 2}}})
               (action-trace/progress->action
                {:event :step/completed :step-id :overview
                 :skill-id :builtin/overview-synthesis :duration-ms 40
                 :outputs {:overview-response "A documented answer [1]."
                           :citations [{:index 1}]}})
               (action-trace/progress->action
                {:event :step/completed :step-id :finalize
                 :skill-id :builtin/overview-finalize :duration-ms 5
                 :outputs {:response "A documented answer [1]."
                           :overview-declined? false
                           :citations [{:index 1}]}})]
        completed-vm (live-next/diagnostics->view-model
                      :en {:status :complete :action-trace trace})
        live-vm (live-next/stream-view->view-model
                 :en {:running? true :action-trace trace})
        completed-actions (:actions completed-vm)
        live-actions (:actions live-vm)]
    (is (= [:plan :search :rerank :sufficiency :synthesis :finalize]
           (mapv :kind completed-actions)))
    (is (= (mapv :action-result live-actions)
           (mapv :action-result completed-actions)))
    (is (= ["primary query" "broader query"]
           (get-in completed-actions [0 :action-result :queries])))
    (is (= 10 (get-in completed-actions [1 :action-result :candidate-count])))
    (is (= 2 (get-in completed-actions [2 :action-result :selected-count])))
    (is (true? (get-in completed-actions [3 :action-result :sufficient?])))
    (is (= "A documented answer [1]."
           (get-in completed-actions [4 :action-result :response])))
    (is (true? (get-in completed-actions [5 :action-result :published?])))))

(deftest legacy-completed-graph-timings-do-not-infer-results
  (let [vm (live-next/diagnostics->view-model
            :en {:status :complete
                 :execution-stage-timings
                 [{:step-id :plan :skill-id :builtin/query-planner
                   :stage :query-planner :status :ok :duration-ms 10}]})
        action (first (:actions vm))]
    (is (true? (:legacy? action)))
    (is (= :plan (:kind action)))
    (is (nil? (:action-result action)))))

(deftest trace-fragments-are-coalesced-by-agent-iteration
  (let [trace [{:iteration 0
                :tool-calls [{:tool "agent-setup"}]}
               {:iteration 0
                :reasoning "**Inspect** the evidence"
                :tool-calls [{:tool "agent-bundle-llm-and-tools"}]}
               {:iteration 1
                :tool-calls [{:tool "search_documents"}]}]
        coalesced (live-next/coalesce-trace-turns trace)]
    (is (= 2 (count coalesced)))
    (is (= ["agent-setup" "agent-bundle-llm-and-tools"]
           (mapv :tool (:tool-calls (first coalesced)))))
    (is (= "**Inspect** the evidence" (:reasoning (first coalesced))))
    (is (= 1 (:iteration (second coalesced))))))

(deftest agent-orchestration-tools-are-not-user-visible-actions
  (let [trace [{:iteration 0
                :reasoning "Search for documented evidence"
                :tool-calls [{:tool "agent-setup"}
                             {:tool "agent-iter-state"}
                             {:tool "agent-bundle-llm-and-tools"}
                             {:tool "search_documents" :result-summary "1 result"}
                             {:tool "agent-bundle-evaluate-evidence"}
                             {:tool "agent-bundle-sufficiency-gates"}
                             {:tool "agent-bundle-record-and-route"}]}]
        stage-timings [{:iteration 0 :stage :agent-llm
                        :status :ok :duration-ms 20}]
        live-vm (live-next/stream-view->view-model
                 :en {:running? true
                      :live-agent-trace trace
                      :live-agent-stage-timings stage-timings})
        completed-vm (live-next/diagnostics->view-model
                      :en {:status :complete
                           :agent-trace trace
                           :agent-stage-timings stage-timings})
        failed-wrapper-vm (live-next/stream-view->view-model
                           :en {:running? true
                                :live-agent-trace
                                [{:iteration 0
                                  :tool-calls [{:tool "agent-iter-state"
                                                :ok? false}]}]})]
    (testing "the LLM turn and real tool remain visible"
      (is (= [:llm :search]
             (mapv :kind (live-next/timeline-actions (:iterations live-vm)))))
      (is (= [:llm :search]
             (mapv :kind (live-next/timeline-actions (:iterations completed-vm))))))
    (testing "internal wrappers do not influence the iteration summary"
      (is (= "Retrieval search"
             (get-in live-vm [:iterations 0 :gist]))))
    (testing "failed orchestration remains available for diagnosis"
      (is (= [:tool]
             (mapv :kind
                   (live-next/timeline-actions (:iterations failed-wrapper-vm))))))))
