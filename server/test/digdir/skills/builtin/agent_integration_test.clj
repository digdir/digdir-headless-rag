(ns digdir.skills.builtin.agent-integration-test
  "Integration tests for the agentic loop using fixture-driven deterministic replay.

   Each test loads an EDN fixture that scripts LLM responses and tool results,
   then runs the agentic loop with those mocks to verify loop behaviour.

   Two replay modes:
   - Shallow: mocks call-llm + execute-tool-call (tests loop decision-making)
   - Deep: mocks call-llm + execute-sub-skill (tests workspace accumulation)"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [digdir.rag.core :as rag]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.skills.builtin.agent.tools :as tools]
            [digdir.skills.builtin.agent.loop :as loop]
            [digdir.skills.test-helpers :as th]))

;; =============================================================================
;; Performance Budgets
;;
;; Document observed behaviour; future improvements tighten these.
;; =============================================================================

(def performance-budgets
  {:quick-win        {:max-iterations 4  :max-tool-calls 3  :max-chunks 45}
   :doubled-search   {:max-iterations 7  :max-tool-calls 7  :max-chunks 80}
   :extended-retry   {:max-iterations 10 :max-tool-calls 10 :max-chunks 120}})

;; =============================================================================
;; Fixture Loading
;; =============================================================================

(defn- load-fixture
  [name]
  (let [path (str "fixtures/agent/" name ".edn")
        resource (io/resource path)]
    (when-not resource
      (throw (ex-info (str "Missing fixture: " path) {:path path})))
    (edn/read-string (slurp resource))))

;; =============================================================================
;; Mock Factories — Shallow Mode
;; =============================================================================

(def ^:private make-scripted-call-llm
  "Alias for the shared test helper."
  th/make-scripted-llm)

(defn- make-scripted-execute-tool-call
  "Returns a fn that returns tool results from the fixture by call order.
   Logs each tool invocation to the provided atom."
  [tool-results !tool-log]
  (let [call-idx (atom -1)]
    (fn [tool-name args _workspace _ambient-ctx]
      (let [i (swap! call-idx inc)]
        (swap! !tool-log conj {:tool tool-name :args args :index i})
        (when (>= i (count tool-results))
          (throw (ex-info "Ran out of scripted tool results"
                          {:index i :tool tool-name :total (count tool-results)})))
        (nth tool-results i)))))

;; =============================================================================
;; Mock Factories — Deep Mode
;; =============================================================================

(defn- make-synthetic-chunks
  "Generate n synthetic chunks with a common prefix for workspace testing."
  [n prefix]
  (mapv (fn [i]
          {:chunk_id (str prefix "-chunk-" i)
           :content_markdown (str "Content for chunk " i " from " prefix)
           :doc_num (str (* 1000 i))
           :metadata {}})
        (range n)))

(defn- make-scripted-execute-sub-skill
  "Returns a fn that returns sub-skill results by skill-id + call count.
   Keys are formatted as 'skill-name_N' where N starts at 1."
  [sub-skill-results]
  (let [call-counts (atom {})]
    (fn [skill-id _inputs _opts & [_parameters]]
      (let [n (get (swap! call-counts update skill-id (fnil inc 0)) skill-id)
            key (str (name skill-id) "_" n)]
        (or (get sub-skill-results key)
            (throw (ex-info (str "No scripted sub-skill result for " key)
                            {:key key :skill-id skill-id})))))))

;; =============================================================================
;; Test Runners
;; =============================================================================

(defn- run-fixture
  "Shallow mode: mock call-llm + execute-tool-call, run agentic-loop.
   Returns {:result :tool-log :workspace :initial-messages}."
  [fixture]
  (let [{:keys [query llm-responses tool-results max-iterations
                conversation-history]} fixture
        !workspace (workspace/create-workspace)
        !tool-log (atom [])
        initial-messages (loop/build-initial-messages
                           loop/default-system-prompt
                           query
                           conversation-history)
        ambient-ctx {:docs-collection "test-docs"
                     :chunks-collection "test-chunks"
                     :phrases-collection "test-phrases"
                     :conversation-history (or conversation-history [])
                     :opts {:tenant "test" :environment "test"}}
        result (with-redefs [loop/call-llm
                             (make-scripted-call-llm llm-responses)
                             ;; The common seam under BOTH dispatch paths:
                             ;; execute-tool-call (legacy/inline) and
                             ;; execute-tool-call-pure (the :builtin/agent-tool-call
                             ;; skill the loop uses once that skill is registered)
                             ;; both bottom out here. Mocking a wrapper instead
                             ;; only intercepted the fallback, so these tests
                             ;; passed only while the registry happened to be
                             ;; missing that skill (#91).
                             tools/execute-tool-call*
                             (make-scripted-execute-tool-call tool-results !tool-log)]
                 (loop/agentic-loop
                   initial-messages
                   !workspace
                   ambient-ctx
                   {:max-iterations (or max-iterations 10)
                    :sufficiency-llm-fn nil}))]
    {:result result
     :tool-log @!tool-log
     :workspace @!workspace
     :initial-messages initial-messages}))

(defn- run-fixture-with-progress
  "Shallow mode with progress callback capture."
  [fixture]
  (let [{:keys [query llm-responses tool-results max-iterations
                conversation-history]} fixture
        !workspace (workspace/create-workspace)
        !tool-log (atom [])
        !progress (atom [])
        initial-messages (loop/build-initial-messages
                           loop/default-system-prompt
                           query
                           conversation-history)
        ambient-ctx {:docs-collection "test-docs"
                     :chunks-collection "test-chunks"
                     :phrases-collection "test-phrases"
                     :conversation-history (or conversation-history [])
                     :opts {:tenant "test" :environment "test"}}
        result (with-redefs [loop/call-llm
                             (make-scripted-call-llm llm-responses)
                             ;; The common seam under BOTH dispatch paths:
                             ;; execute-tool-call (legacy/inline) and
                             ;; execute-tool-call-pure (the :builtin/agent-tool-call
                             ;; skill the loop uses once that skill is registered)
                             ;; both bottom out here. Mocking a wrapper instead
                             ;; only intercepted the fallback, so these tests
                             ;; passed only while the registry happened to be
                             ;; missing that skill (#91).
                             tools/execute-tool-call*
                             (make-scripted-execute-tool-call tool-results !tool-log)]
                 (loop/agentic-loop
                   initial-messages
                   !workspace
                   ambient-ctx
                   {:max-iterations (or max-iterations 10)
                    :progress-fn #(swap! !progress conj %)
                    :sufficiency-llm-fn nil}))]
    {:result result
     :tool-log @!tool-log
     :workspace @!workspace
     :progress @!progress}))

(defn- run-fixture-deep
  "Deep mode: mock call-llm + execute-sub-skill, real execute-tool-call.
   Returns {:result :workspace}."
  [fixture sub-skill-results]
  (let [{:keys [query llm-responses max-iterations conversation-history]} fixture
        !workspace (workspace/create-workspace)
        initial-messages (loop/build-initial-messages
                           loop/default-system-prompt
                           query
                           conversation-history)
        ambient-ctx {:docs-collection "test-docs"
                     :chunks-collection "test-chunks"
                     :phrases-collection "test-phrases"
                     :conversation-history (or conversation-history [])
                     :opts {:tenant "test" :environment "test"}}
        result (with-redefs [loop/call-llm
                             (make-scripted-call-llm llm-responses)
                             tools/execute-sub-skill
                             (make-scripted-execute-sub-skill sub-skill-results)]
                 (loop/agentic-loop
                   initial-messages
                   !workspace
                   ambient-ctx
                   {:max-iterations (or max-iterations 10)
                    :sufficiency-llm-fn nil}))]
    {:result result
     :workspace @!workspace}))

(defn- tool-call-response
  [content tool-name args call-id]
  {:choices [{:finish_reason "tool_calls"
              :message {:content content
                        :tool_calls [{:id call-id
                                      :type "function"
                                      :function {:name tool-name
                                                 :arguments (json/write-str args)}}]}}]})

(defn- final-response
  [content]
  {:choices [{:finish_reason "stop"
              :message {:content content}}]})

(defn- search-budget-exhausted?
  [text]
  (boolean (re-find #"Search budget exhausted" (or text ""))))

(defn- read-budget-exhausted?
  [text]
  (boolean (re-find #"Read budget exhausted" (or text ""))))

(defn- sufficiency-gate-rejected?
  [text]
  (boolean (re-find #"Sufficiency gate rejected" (or text ""))))

(def ^:private default-read-signals-llm-response
  "Deterministic JSON for the read-time local evaluator used by deep-mode
   integration tests. Emits a purposely-ambiguous signal so the aggregator
   stays at :ambiguous and the shadow gate bails out — letting the existing
   LLM sufficiency gate (or its heuristic fallback) own the decision. This
   preserves the pre-read-signal-infrastructure test semantics. Individual
   tests can override via :read-signals-llm-fn in :opts when they want to
   exercise the shadow-gate path directly."
  "{\"status\":\"unclear\",\"scope_assessment\":\"ambiguous\",\"supported_claims\":[],\"remaining_gaps\":[],\"contradictions\":[],\"next_action_hint\":\"re-search\",\"confidence\":0.3}")

(defn- default-read-signals-llm-fn
  [_messages _tools _model _temperature]
  {:choices [{:message {:content default-read-signals-llm-response}}]})

(defn- run-budgeted-deep
  [{:keys [query max-iterations conversation-history budget-limits
           call-llm execute-sub-skill retrieve-by-id retrieve-by-range
           read-signals-llm-fn]}]
  (let [!workspace (workspace/create-workspace)
        initial-messages (loop/build-initial-messages
                           loop/default-system-prompt
                           query
                           conversation-history)
        ambient-ctx {:docs-collection "test-docs"
                     :chunks-collection "test-chunks"
                     :phrases-collection "test-phrases"
                     :conversation-history (or conversation-history [])
                     :opts {:tenant "test"
                            :environment "test"
                            :read-signals-llm-fn (or read-signals-llm-fn
                                                     default-read-signals-llm-fn)}}
        retrieve-by-id (or retrieve-by-id (fn [& _] []))
        retrieve-by-range (or retrieve-by-range (fn [& _] []))]
    (swap! !workspace assoc :budget-limits budget-limits)
    (with-redefs [loop/call-llm call-llm
                  tools/execute-sub-skill execute-sub-skill
                  rag/retrieve-chunks-by-id retrieve-by-id
                  rag/retrieve-chunks-by-range retrieve-by-range]
      (let [result (loop/agentic-loop
                     initial-messages
                     !workspace
                     ambient-ctx
                     {:max-iterations (or max-iterations 20)
                      :sufficiency-llm-fn nil})]
        {:result result
         :workspace @!workspace}))))

(defn- tool-sequence
  "Extract ordered tool names from the tool log."
  [tool-log]
  (mapv :tool tool-log))

(defn- trace-tool-sequence
  "Extract ordered tool names from the agent trace."
  [trace]
  (mapv :tool (mapcat :tool-calls trace)))

;; =============================================================================
;; Pattern A: Quick Win (3 iterations, 1 search, auto-filter, success)
;; Source: agent-trace-2026-02-10T11-23-56-500249Z.txt
;; =============================================================================

(deftest quick-win-completes-in-three-iterations
  (testing "Quick-win pattern: search, rerank, generate completes in 3 tool iterations"
    (let [fixture (load-fixture "01_quick_win")
          {:keys [result]} (run-fixture fixture)]
      (is (some? (:response result)) "Should produce a response")
      (is (nil? (:error result)) "Should not have errors")
      (is (not (:exhausted result)) "Should not exhaust iterations")
      (is (= 3 (count (:trace result))) "Should complete in 3 iterations")
      (is (re-find #"330" (:response result))
          "Response should contain the FTE number"))))

(deftest quick-win-tool-call-order
  (testing "Quick-win pattern: tools are called in search, rerank, generate order"
    (let [fixture (load-fixture "01_quick_win")
          {:keys [tool-log]} (run-fixture fixture)]
      (is (= ["search_documents" "rerank_results" "generate_response"]
             (tool-sequence tool-log))))))

(deftest quick-win-emits-agent-progress-events
  (testing "Quick-win emits iteration, tool call/result, and finalized progress events"
    (let [fixture (load-fixture "01_quick_win")
          {:keys [progress]} (run-fixture-with-progress fixture)
          event-kinds (mapv :event progress)]
      (is (some #{:agent/iteration-started} event-kinds))
      (is (some #{:agent/tool-call} event-kinds))
      (is (some #{:agent/tool-result} event-kinds))
      (is (some #{:agent/finalized} event-kinds)))))

(deftest quick-win-within-performance-budget
  (testing "Quick-win pattern stays within performance budget"
    (let [fixture (load-fixture "01_quick_win")
          {:keys [result tool-log]} (run-fixture fixture)
          budget (:quick-win performance-budgets)]
      (is (<= (count (:trace result)) (:max-iterations budget)))
      (is (<= (count tool-log) (:max-tool-calls budget))))))

;; =============================================================================
;; Pattern B: Doubled Search (insufficient-context retry)
;; Source: agent-trace-2026-02-10T13-35-08-402281Z.txt (adapted for retry)
;; =============================================================================

(deftest doubled-search-insufficient-context-triggers-retry
  (testing "Doubled-search: first generate signals insufficient context, loop retries"
    (let [fixture (load-fixture "02_doubled_search")
          {:keys [tool-log]} (run-fixture fixture)
          tool-names (tool-sequence tool-log)
          generate-indices (keep-indexed
                             (fn [i t] (when (= "generate_response" t) i))
                             tool-names)]
      (is (>= (count generate-indices) 2)
          "Should call generate_response at least twice (retry after insufficient)"))))

(deftest doubled-search-second-round-converges
  (testing "Doubled-search: second search+rerank+generate round succeeds"
    (let [fixture (load-fixture "02_doubled_search")
          {:keys [result]} (run-fixture fixture)]
      (is (not (:exhausted result)) "Should converge before exhaustion")
      (is (re-find #"330" (:response result))
          "Second round should find the answer"))))

(deftest doubled-search-tool-sequence-matches
  (testing "Doubled-search: full tool sequence matches expected two-cycle pattern"
    (let [fixture (load-fixture "02_doubled_search")
          {:keys [tool-log]} (run-fixture fixture)]
      (is (= ["search_documents" "search_documents" "rerank_results"
              "generate_response" "search_documents" "rerank_results"
              "generate_response"]
             (tool-sequence tool-log))))))

(deftest doubled-search-within-performance-budget
  (testing "Doubled-search stays within performance budget"
    (let [fixture (load-fixture "02_doubled_search")
          {:keys [result tool-log]} (run-fixture fixture)
          budget (:doubled-search performance-budgets)]
      (is (<= (count (:trace result)) (:max-iterations budget)))
      (is (<= (count tool-log) (:max-tool-calls budget))))))

;; =============================================================================
;; Pattern C: Extended Retry (7 iterations, diminishing returns, exhaustion)
;; Source: agent-trace-2026-02-09T18-02-26-422248Z.txt
;; =============================================================================

(deftest extended-retry-reaches-iteration-limit
  (testing "Extended-retry: loop reaches iteration limit and triggers exhaustion fallback"
    (let [fixture (load-fixture "03_extended_retry")
          {:keys [result]} (run-fixture fixture)]
      (is (:exhausted result) "Should flag exhaustion")
      (is (some? (:response result)) "Should still produce a fallback response"))))

(deftest extended-retry-tool-call-growth
  (testing "Extended-retry: documents escalating tool call pattern"
    (let [fixture (load-fixture "03_extended_retry")
          {:keys [tool-log]} (run-fixture fixture)
          budget (:extended-retry performance-budgets)]
      (is (>= (count tool-log) 8) "Extended pattern uses many tool calls")
      (is (<= (count tool-log) (:max-tool-calls budget))
          "Should stay within extended budget"))))

(deftest extended-retry-includes-plan-queries
  (testing "Extended-retry: uses plan_queries to diversify search terms"
    (let [fixture (load-fixture "03_extended_retry")
          {:keys [tool-log]} (run-fixture fixture)]
      (is (some #(= "plan_queries" (:tool %)) tool-log)
          "Should use plan_queries for query expansion"))))

(deftest extended-retry-tool-sequence-matches
  (testing "Extended-retry: tool sequence matches expected escalating pattern"
    (let [fixture (load-fixture "03_extended_retry")
          {:keys [tool-log]} (run-fixture fixture)]
      (is (= ["search_documents" "search_documents" "rerank_results"
              "generate_response" "plan_queries" "search_documents"
              "rerank_results" "generate_response"]
             (tool-sequence tool-log))))))

;; =============================================================================
;; Filter Fallback (explicit filter -> 0 chunks -> unfiltered retry)
;; Source: agent-trace-2026-02-09T17-35-49-476647Z.txt
;; =============================================================================

(deftest filter-fallback-retries-unfiltered
  (testing "Filter-fallback: tool result indicates filter returned 0 and retried"
    (let [fixture (load-fixture "04_filter_fallback")
          {:keys [tool-log]} (run-fixture fixture)
          first-tool-result (nth (:tool-results fixture) 0)]
      (is (re-find #"[Ff]iltered.*0.*retried without filters" first-tool-result)
          "First search result should describe filter fallback")
      (is (= "search_documents" (:tool (first tool-log)))
          "First tool should be search_documents"))))

(deftest filter-fallback-converges
  (testing "Filter-fallback: proceeds to successful answer after fallback"
    (let [fixture (load-fixture "04_filter_fallback")
          {:keys [result]} (run-fixture fixture)]
      (is (some? (:response result)))
      (is (nil? (:error result)))
      (is (not (:exhausted result)))
      (is (re-find #"330" (:response result))))))

;; =============================================================================
;; Conversation History
;; =============================================================================

(deftest conversation-history-included-in-messages
  (testing "Conversation history: prior turns appear in initial messages"
    (let [fixture (load-fixture "05_conversation_history")
          {:keys [initial-messages]} (run-fixture fixture)]
      ;; System + prior user + prior assistant + current user = 4 messages
      (is (= 4 (count initial-messages)))
      (is (= "system" (:role (first initial-messages))))
      (is (= "user" (:role (second initial-messages))))
      (is (re-find #"årsverk" (:content (second initial-messages)))
          "Prior user turn should be present")
      (is (= "assistant" (:role (nth initial-messages 2)))))))

(deftest conversation-history-query-is-follow-up
  (testing "Conversation history: current query is the follow-up question"
    (let [fixture (load-fixture "05_conversation_history")
          {:keys [initial-messages]} (run-fixture fixture)]
      (is (= "Hvordan fordeler de seg?" (:content (last initial-messages)))))))

(deftest conversation-history-produces-response
  (testing "Conversation history: follow-up query produces a response"
    (let [fixture (load-fixture "05_conversation_history")
          {:keys [result]} (run-fixture fixture)]
      (is (some? (:response result)))
      (is (nil? (:error result)))
      (is (not (:exhausted result))))))

;; =============================================================================
;; Deep Mode: Workspace Assertions
;; =============================================================================

(deftest workspace-metadata-only-search-does-not-populate-chunks
  (testing "Deep mode: search stores metadata history but does not populate full chunks"
    (let [fixture (load-fixture "01_quick_win")
          chunks (make-synthetic-chunks 40 "search1")
          context-docs (mapv (fn [c]
                               {:page_content (:content_markdown c)
                                :metadata {:source (:chunk_id c)}})
                             (take 10 chunks))
          sub-skill-results
          {"retrieval_1" {:outputs {:chunks chunks
                                    :search-attribution {:phrase "test"
                                                         :metadata 82
                                                         :content 54}}}
           "rerank_1"    {:outputs {:reranked-chunks (take 10 chunks)
                                    :context-docs context-docs}}
           "synthesis_1" {:outputs {:response "Digdir hadde 330 utførte årsverk i 2022 [7]."}}}
          {:keys [workspace]} (run-fixture-deep fixture sub-skill-results)]
      (is (= 0 (count (:chunks workspace)))
          "Metadata-only search should not add full chunks to workspace")
      (is (= 1 (count (:search-history workspace))))
      (is (= 40 (-> workspace :search-history first :result-count)))
      (is (= 0 (count (:read-history workspace)))))))

(deftest workspace-deduplicates-seen-search-results-across-searches
  (testing "Deep mode: repeated metadata searches deduplicate seen chunk ids"
    (let [fixture (load-fixture "02_doubled_search")
          shared-chunks (make-synthetic-chunks 20 "shared")
          search1-only (make-synthetic-chunks 20 "s1-only")
          search2-only (make-synthetic-chunks 21 "s2-only")
          search1-chunks (into search1-only shared-chunks)
          search2-chunks (into search2-only shared-chunks)
          search3-chunks (make-synthetic-chunks 15 "s3-only")
          all-reranked (take 10 search1-chunks)
          context-docs (mapv (fn [c]
                               {:page_content (:content_markdown c)
                                :metadata {:source (:chunk_id c)}})
                             all-reranked)
          sub-skill-results
          {"retrieval_1" {:outputs {:chunks search1-chunks
                                    :search-attribution {:phrase "s1" :metadata 40 :content 30}}}
           "retrieval_2" {:outputs {:chunks search2-chunks
                                    :search-attribution {:phrase "s2" :metadata 40 :content 30}}}
           "rerank_1"    {:outputs {:reranked-chunks all-reranked
                                    :context-docs context-docs}}
           "synthesis_1" {:outputs {:response "Insufficient context."
                                    :insufficient-context true}}
           "retrieval_3" {:outputs {:chunks search3-chunks
                                    :search-attribution {:phrase "s3" :metadata 20 :content 15}}}
           "rerank_2"    {:outputs {:reranked-chunks all-reranked
                                    :context-docs context-docs}}
           "synthesis_2" {:outputs {:response "Digdir hadde 330 utførte årsverk i 2022 [7]."}}}
          {:keys [workspace]} (run-fixture-deep fixture sub-skill-results)]
      ;; 20 s1-only + 20 shared + 21 s2-only + 15 s3-only = 76 unique
      (is (= 0 (count (:chunks workspace)))
          "Search-only flow should still have no full chunks loaded")
      (is (= 3 (count (:search-history workspace))))
      (is (= 76 (count (:seen-search-chunk-ids workspace)))
          "Seen chunk ids should deduplicate shared hits across searches"))))

(deftest workspace-search-history-pattern-c
  (testing "Deep mode: extended retry records broad search coverage without reading content"
    (let [fixture (load-fixture "03_extended_retry")
          search1-chunks (make-synthetic-chunks 40 "s1")
          shared-with-s1 (take 8 search1-chunks)
          search2-only (make-synthetic-chunks 32 "s2-only")
          search2-chunks (into search2-only shared-with-s1)
          search3-only (make-synthetic-chunks 32 "s3-only")
          search3-chunks (into search3-only shared-with-s1)
          all-reranked (take 10 search1-chunks)
          context-docs (mapv (fn [c]
                               {:page_content (:content_markdown c)
                                :metadata {:source (:chunk_id c)}})
                             all-reranked)
          sub-skill-results
          {"retrieval_1"     {:outputs {:chunks search1-chunks
                                        :search-attribution {:phrase "s1" :metadata 40 :content 30}}}
           "retrieval_2"     {:outputs {:chunks search2-chunks
                                        :search-attribution {:phrase "s2" :metadata 30 :content 20}}}
           "rerank_1"        {:outputs {:reranked-chunks all-reranked
                                        :context-docs context-docs}}
           "synthesis_1"     {:outputs {:response "Insufficient context."
                                        :insufficient-context true}}
           "query-planner_1" {:outputs {:search-phrases ["expanded query 1" "expanded query 2"]}}
           "retrieval_3"     {:outputs {:chunks search3-chunks
                                        :search-attribution {:phrase "s3" :metadata 30 :content 20}}}
           "rerank_2"        {:outputs {:reranked-chunks all-reranked
                                        :context-docs context-docs}}
           "synthesis_2"     {:outputs {:response "Still insufficient."
                                        :insufficient-context true}}}
          {:keys [workspace]} (run-fixture-deep fixture sub-skill-results)]
      ;; 40 s1 + 32 s2-only + 32 s3-only = 104 unique
      (is (= 0 (count (:chunks workspace)))
          "Without read_chunks calls, full chunk workspace should stay empty")
      (is (= 3 (count (:search-history workspace))))
      (is (<= 100 (count (:seen-search-chunk-ids workspace)))
          "Extended retry should still cover a large candidate set")
      (is (= 0 (:read-content-length workspace))
          "No reads means no content budget consumed"))))

;; =============================================================================
;; Improved Retrieval (limit increase recovers target chunk)
;; =============================================================================

(deftest improved-retrieval-finds-target-chunk
  (testing "Improved retrieval: converges in 3 iterations with correct answer (326 årsverk)"
    (let [fixture (load-fixture "06_improved_retrieval")
          {:keys [result]} (run-fixture fixture)]
      (is (some? (:response result)) "Should produce a response")
      (is (nil? (:error result)) "Should not have errors")
      (is (not (:exhausted result)) "Should not exhaust iterations")
      (is (= 3 (count (:trace result))) "Should complete in 3 iterations")
      (is (re-find #"326" (:response result))
          "Response should contain the correct FTE number (326)"))))

(deftest improved-retrieval-tool-call-order
  (testing "Improved retrieval: tools called in search, rerank, generate order"
    (let [fixture (load-fixture "06_improved_retrieval")
          {:keys [tool-log]} (run-fixture fixture)]
      (is (= ["search_documents" "rerank_results" "generate_response"]
             (tool-sequence tool-log))))))

;; =============================================================================
;; Evidence-Aware Second Pass (structured insufficiency -> targeted re-search -> success)
;; =============================================================================

(deftest evidence-aware-second-pass-succeeds-after-targeted-research
  (testing "Deep mode: first pass records insufficiency, second pass follows targeted re-search and succeeds"
    (let [fixture (load-fixture "07_evidence_aware_second_pass")
          broad-metadata [{:chunk_id "c1"
                           :doc_num "d1"
                           :chunk_index 0
                           :content_length 80
                           :metadata "{:header \"Bemanning\"}"
                           :test-docs {:title "HR og bemanning" :total_chunks 1}}]
          targeted-metadata [{:chunk_id "c2"
                              :doc_num "d2"
                              :chunk_index 0
                              :content_length 120
                              :metadata "{:header \"Hovudtal\"}"
                              :test-docs {:title "Årsrapport Digdir 2022" :total_chunks 1}}]
          broad-read [{:chunk_id "c1"
                       :doc_num "d1"
                       :chunk_index 0
                       :content_markdown "Bemanningsomtale uten eksakt årsverkstall."
                       :content_length 42
                       :metadata "{:header \"Bemanning\"}"
                       :test-docs {:title "HR og bemanning" :total_chunks 1}}]
          targeted-read [{:chunk_id "c2"
                          :doc_num "d2"
                          :chunk_index 0
                          :content_markdown "Digdir besto 31.12.2022 av 326 utførte årsverk fordelt på 345 faste og 15 midlertidige stillinger."
                          :content_length 104
                          :metadata "{:header \"Hovudtal\"}"
                          :test-docs {:title "Årsrapport Digdir 2022" :total_chunks 1}}]
          sub-skill-results
          {"retrieval_1" {:outputs {:chunks broad-metadata
                                    :search-attribution {:phrase 0 :metadata 4 :content 2}}}
           "rerank_1"    {:outputs {:reranked-chunks broad-read
                                    :context-docs [{:page_content (:content_markdown (first broad-read))
                                                    :metadata {:source "c1"}}]}}
           "synthesis_1" {:outputs {:response "Fant ikke et eksakt tall."
                                    :insufficient-context true}}
           "retrieval_2" {:outputs {:chunks targeted-metadata
                                    :search-attribution {:phrase 0 :metadata 6 :content 8}}}
           "rerank_2"    {:outputs {:reranked-chunks targeted-read
                                    :context-docs [{:page_content (:content_markdown (first targeted-read))
                                                    :metadata {:source "c2"}}]}}
           "synthesis_2" {:outputs {:response "Digdir hadde 326 utførte årsverk in 2022 [1]."}}}
          !read-calls (atom [])
          {:keys [result workspace]} (with-redefs [loop/call-llm
                                                   (make-scripted-call-llm (:llm-responses fixture))
                                                   tools/execute-sub-skill
                                                   (make-scripted-execute-sub-skill sub-skill-results)
                                                   rag/retrieve-chunks-by-id
                                                   (fn [_docs _chunks id-list _opts]
                                                     (let [ids (mapv :chunk_id id-list)]
                                                       (swap! !read-calls conj ids)
                                                       (cond
                                                         (= ids ["c1"]) broad-read
                                                         (= ids ["c2"]) targeted-read
                                                         :else [])))]
                                 (let [!workspace (workspace/create-workspace)
                                       initial-messages (loop/build-initial-messages
                                                         loop/default-system-prompt
                                                         (:query fixture)
                                                         [])
                                       ambient-ctx {:docs-collection "test-docs"
                                                    :chunks-collection "test-chunks"
                                                    :phrases-collection "test-phrases"
                                                    :conversation-history []
                                                    :opts {:tenant "test"
                                                           :environment "test"
                                                           :read-signals-llm-fn default-read-signals-llm-fn}}
                                       result (loop/agentic-loop initial-messages !workspace ambient-ctx
                                                                  {:max-iterations (:max-iterations fixture)
                                                                   :sufficiency-llm-fn nil})]
                                   {:result result
                                    :workspace @!workspace}))]
      (is (some? (:response result)))
      (is (re-find #"326" (:response result)))
      (is (= ["search" "read_chunks" "rerank_results" "generate_response"
              "search" "read_chunks" "rerank_results" "generate_response"]
             (trace-tool-sequence (:trace result))))
      (is (= [["c1"] ["c2"]] @!read-calls))
      (is (= 2 (count (:search-history workspace))))
      (is (= 2 (count (:read-history workspace))))
      (is (= [:insufficient :conflicting]
             (mapv :status (:sufficiency-decisions workspace))))
      (is (= :missing-numeric-fact
             (get-in workspace [:sufficiency-decisions 0 :insufficiency :failure-type])))
      (is (sufficiency-gate-rejected?
           (get-in result [:trace 3 :tool-calls 0 :result-summary])))
      (is (re-find #"exact reported value"
                   (get-in result [:trace 3 :tool-calls 0 :result-summary])))
      (is (= ["digdir årsrapport 2022 årsverk"
              "digdir årsverk 2022 oppgitt tall"]
             (get-in result [:trace 4 :tool-calls 0 :args :queries]))))))

(deftest march16-broad-read-trace-preserves-budget-for-decisive-table-read
  (testing "Deep mode: a broad annual-report range read is clamped so the decisive table chunk can still be read"
    (let [fixture (load-fixture "08_broad_read_budget")
          annual-report-title "Årsrapport Digitaliseringsdirektoratet 2022"
          broad-metadata [{:chunk_id "turnover"
                           :doc_num "33169"
                           :chunk_index 168
                           :content_length 636
                           :metadata "{\"Header 1\" \"0 20 40 60 80 100 2020 2021 2022 Faste ansatte i Digdir 31.12.2022\"}"
                           :test-docs {:title annual-report-title :total_chunks 220}}
                          {:chunk_id "nearby-a"
                           :doc_num "33169"
                           :chunk_index 167
                           :content_length 1100
                           :metadata "{\"Header 1\" \"Likestilling\"}"
                           :test-docs {:title annual-report-title :total_chunks 220}}
                          {:chunk_id "nearby-b"
                           :doc_num "33169"
                           :chunk_index 169
                           :content_length 1100
                           :metadata "{\"Header 1\" \"Forklaring\"}"
                           :test-docs {:title annual-report-title :total_chunks 220}}
                          {:chunk_id "nearby-c"
                           :doc_num "33169"
                           :chunk_index 170
                           :content_length 1100
                           :metadata "{\"Header 1\" \"Aldersfordeling\"}"
                           :test-docs {:title annual-report-title :total_chunks 220}}
                          {:chunk_id "table"
                           :doc_num "33169"
                           :chunk_index 20
                           :content_length 3000
                           :metadata "{\"Header 1\" \"Hovudtal\"}"
                           :test-docs {:title annual-report-title :total_chunks 220}}]
          turnover-read [{:chunk_id "turnover"
                          :doc_num "33169"
                          :chunk_index 168
                          :content_markdown "Turnovertallet for Digdir i 2022 er beregnet ut fra antall ansatte pr. 31.12.2022."
                          :content_length 636
                          :metadata "{\"Header 1\" \"0 20 40 60 80 100 2020 2021 2022 Faste ansatte i Digdir 31.12.2022\"}"
                          :test-docs {:title annual-report-title :total_chunks 220}}]
          table-read [{:chunk_id "table"
                       :doc_num "33169"
                       :chunk_index 20
                       :content_markdown "| Tal avtalte årsverk | 356 |\n| Tal utførte årsverk | 326 |"
                       :content_length 3000
                       :metadata "{\"Header 1\" \"Hovudtal\"}"
                       :test-docs {:title annual-report-title :total_chunks 220}}]
          sub-skill-results
          {"retrieval_1" {:outputs {:chunks broad-metadata
                                    :search-attribution {:phrase 0 :metadata 5 :content 4}}}
           "rerank_1"    {:outputs {:reranked-chunks table-read
                                    :context-docs [{:page_content (:content_markdown (first table-read))
                                                    :metadata {:source "table"}}]}}
           "synthesis_1" {:outputs {:response "Digdir hadde 356 avtalte årsverk og 326 utførte årsverk i 2022 [1]."}}}
          !range-calls (atom [])
          {:keys [result workspace]}
          (with-redefs [loop/call-llm
                        (make-scripted-call-llm (:llm-responses fixture))
                        tools/execute-sub-skill
                        (make-scripted-execute-sub-skill sub-skill-results)
                        rag/retrieve-chunks-by-id
                        (fn [_docs _chunks id-list _opts]
                          (let [ids (mapv :chunk_id id-list)]
                            (cond
                              (= ids ["turnover"]) turnover-read
                              (= ids ["table"]) table-read
                              :else [])))
                        rag/retrieve-chunks-by-range
                        (fn [_docs _chunks doc-num from to _opts]
                          (swap! !range-calls conj {:doc-num doc-num :from from :to to})
                          (mapv (fn [idx]
                                  {:chunk_id (str "range-" idx)
                                   :doc_num doc-num
                                   :chunk_index idx
                                   :content_markdown (apply str (repeat 1100 "x"))
                                   :content_length 1100
                                   :metadata "{}"
                                   :test-docs {:title annual-report-title :total_chunks 220}})
                                (range from (inc to))))]
            (let [!workspace (workspace/create-workspace)
                  _ (swap! !workspace assoc :budget-limits {:max-search-passes 4
                                                           :max-read-operations 6
                                                           :max-read-content-length 12000})
                  initial-messages (loop/build-initial-messages
                                    loop/default-system-prompt
                                    (:query fixture)
                                    [])
                  ambient-ctx {:docs-collection "test-docs"
                               :chunks-collection "test-chunks"
                               :phrases-collection "test-phrases"
                               :conversation-history []
                               :opts {:tenant "test"
                                      :environment "test"
                                      :read-signals-llm-fn default-read-signals-llm-fn}}
                  result (loop/agentic-loop initial-messages !workspace ambient-ctx
                                             {:max-iterations (:max-iterations fixture)
                                              :sufficiency-llm-fn nil})]
              {:result result
               :workspace @!workspace}))]
      (is (some? (:response result)))
      (is (re-find #"326" (:response result)))
      (is (= ["search" "read_chunks" "read_chunks" "read_chunks" "rerank_results" "generate_response"]
             (trace-tool-sequence (:trace result))))
      (is (= [{:doc-num "33169" :from 166 :to 168}] @!range-calls))
      (is (= 3 (count (:read-history workspace))))
      (is (= [:chunk-ids :doc-range :chunk-ids]
             (mapv :mode (:read-history workspace))))
      (is (= 6936 (:read-content-length workspace)))
      (is (= ["table"]
             (-> workspace :read-history last :returned-chunk-ids)))
      (is (= [:insufficient]
             (mapv :status (:sufficiency-decisions workspace)))))))

;; =============================================================================
;; Altinn 3 Exhaustion Fallback (preserve last generated answer on fallback 400)
;; Source: agent-trace-2026-03-30T19-48-18-188404Z.txt
;; =============================================================================

(deftest altinn3-exhaustion-fallback-preserves-last-generated-answer
  (testing "Deep mode: exact Altinn 3 replay keeps the generated answer when the final fallback LLM call fails"
    (let [fixture (load-fixture "09_altinn3_exhaustion_fallback")
          query (:query fixture)
          first-search [{:chunk_id "d81ff032aa35"
                         :doc_num "6bfb44e9124f"
                         :chunk_index 2
                         :content_length 478
                         :metadata "{\"Header 2\" \"Hvorfor Altinn 3?\"}"
                         :test-docs {:title "About" :total_chunks 4}}
                        {:chunk_id "a1a45b9d7585"
                         :doc_num "db112a3d8c92"
                         :chunk_index 4
                         :content_length 2106
                         :metadata "{\"Header 3\" \"Import fra Altinn 2 lenketjenester\", \"Header 4\" \"Migrering av delegeringer\"}"
                         :test-docs {:title "Linked services" :total_chunks 5}}
                        {:chunk_id "05e9fe2c2c5b"
                         :doc_num "db112a3d8c92"
                         :chunk_index 3
                         :content_length 1021
                         :metadata "{\"Header 3\" \"Import fra Altinn 2 lenketjenester\"}"
                         :test-docs {:title "Linked services" :total_chunks 5}}]
          second-search [{:chunk_id "64cc1aee310d"
                          :doc_num "87044ee89679"
                          :chunk_index 4
                          :content_length 1200
                          :metadata "{\"Header 2\" \"Altinn Studio Datamodellering\", \"Header 3\" \"Datamodeller for organisasjoner\"}"
                          :test-docs {:title "Data modeling" :total_chunks 6}}
                         {:chunk_id "2f6cee8d5f5e"
                          :doc_num "87044ee89679"
                          :chunk_index 1
                          :content_length 1073
                          :metadata "{\"Header 2\" \"Altinn Studio Datamodellering\", \"Header 3\" \"Navigere til Altinn Studio Datamodellering\"}"
                          :test-docs {:title "Data modeling" :total_chunks 6}}
                         {:chunk_id "f2c87d24c5be"
                          :doc_num "b35ba60edb88"
                          :chunk_index 0
                          :content_length 990
                          :metadata "{\"Header 2\" \"Hva er Altinn Formidling?\"}"
                          :test-docs {:title "About" :total_chunks 4}}]
          third-search [{:chunk_id "d81ff032aa35"
                         :doc_num "6bfb44e9124f"
                         :chunk_index 2
                         :content_length 478
                         :metadata "{\"Header 2\" \"Hvorfor Altinn 3?\"}"
                         :test-docs {:title "About" :total_chunks 4}}
                        {:chunk_id "a1a45b9d7585"
                         :doc_num "db112a3d8c92"
                         :chunk_index 4
                         :content_length 2106
                         :metadata "{\"Header 3\" \"Import fra Altinn 2 lenketjenester\", \"Header 4\" \"Migrering av delegeringer\"}"
                         :test-docs {:title "Linked services" :total_chunks 5}}
                        {:chunk_id "05e9fe2c2c5b"
                         :doc_num "db112a3d8c92"
                         :chunk_index 3
                         :content_length 1021
                         :metadata "{\"Header 3\" \"Import fra Altinn 2 lenketjenester\"}"
                         :test-docs {:title "Linked services" :total_chunks 5}}]
          first-read [{:chunk_id "d81ff032aa35"
                       :doc_num "6bfb44e9124f"
                       :chunk_index 2
                       :content_markdown "Det er mange grunner til å bruke Altinn 3 til å bygge og kjøre dine digitale tjenester."
                       :content_length 478
                       :metadata "{\"Header 2\" \"Hvorfor Altinn 3?\"}"
                       :test-docs {:title "About" :total_chunks 4}}
                      {:chunk_id "a1a45b9d7585"
                       :doc_num "db112a3d8c92"
                       :chunk_index 4
                       :content_markdown "For de fleste lenketjenester finnes det aktive delegeringer i Altinn 2 som kan migreres til Altinn 3."
                       :content_length 2106
                       :metadata "{\"Header 3\" \"Import fra Altinn 2 lenketjenester\", \"Header 4\" \"Migrering av delegeringer\"}"
                       :test-docs {:title "Linked services" :total_chunks 5}}
                      {:chunk_id "05e9fe2c2c5b"
                       :doc_num "db112a3d8c92"
                       :chunk_index 3
                       :content_markdown "Eksisterende lenketjenester i Altinn 2 må flyttes til Ressursregisteret på Altinn 3."
                       :content_length 1021
                       :metadata "{\"Header 3\" \"Import fra Altinn 2 lenketjenester\"}"
                       :test-docs {:title "Linked services" :total_chunks 5}}]
          second-read [{:chunk_id "64cc1aee310d"
                        :doc_num "87044ee89679"
                        :chunk_index 4
                        :content_markdown "Altinn Studio Datamodellering er et verktøy for å utvikle datamodeller."
                        :content_length 1200
                        :metadata "{\"Header 2\" \"Altinn Studio Datamodellering\", \"Header 3\" \"Datamodeller for organisasjoner\"}"
                        :test-docs {:title "Data modeling" :total_chunks 6}}
                       {:chunk_id "2f6cee8d5f5e"
                        :doc_num "87044ee89679"
                        :chunk_index 1
                        :content_markdown "Logg inn i Altinn Studio og naviger til datamodellering."
                        :content_length 1073
                        :metadata "{\"Header 2\" \"Altinn Studio Datamodellering\", \"Header 3\" \"Navigere til Altinn Studio Datamodellering\"}"
                        :test-docs {:title "Data modeling" :total_chunks 6}}
                       {:chunk_id "f2c87d24c5be"
                        :doc_num "b35ba60edb88"
                        :chunk_index 0
                        :content_markdown "Altinn Formidling gir styrt filoverføring med sikker overføring av store filer."
                        :content_length 990
                        :metadata "{\"Header 2\" \"Hva er Altinn Formidling?\"}"
                        :test-docs {:title "About" :total_chunks 4}}]
          _third-read first-read
          final-response "Basert på den tilgjengelige informasjonen i kildene, er det ingen eksplisitt dato eller år nevnt for lanseringen av Altinn 3. Kildene beskriver funksjonalitet, fordeler og bruksområder for Altinn 3, men gir ingen informasjon om når plattformen ble lansert."
          context-docs (mapv (fn [chunk]
                               {:page_content (:content_markdown chunk)
                                :metadata {:source (:chunk_id chunk)}})
                             first-read)
          sub-skill-results
          {"retrieval_1" {:outputs {:chunks first-search
                                    :search-attribution {:phrase 28 :metadata 19 :content 24}}}
           "rerank_1"    {:outputs {:reranked-chunks first-read
                                    :context-docs context-docs}}
           "retrieval_2" {:outputs {:chunks second-search
                                    :search-attribution {:phrase 31 :metadata 18 :content 29}}}
           "rerank_2"    {:outputs {:reranked-chunks (into first-read second-read)
                                    :context-docs context-docs}}
           "retrieval_3" {:outputs {:chunks third-search
                                    :search-attribution {:phrase 30 :metadata 21 :content 28}}}
           "rerank_3"    {:outputs {:reranked-chunks (into (into [] first-read) second-read)
                                    :context-docs context-docs}}
           "synthesis_1" {:outputs {:response final-response
                                    :insufficient-context true}}}
          !read-calls (atom [])
          {:keys [result workspace]}
          (with-redefs [loop/call-llm
                        (make-scripted-call-llm (:llm-responses fixture))
                        tools/execute-sub-skill
                        (make-scripted-execute-sub-skill sub-skill-results)
                        rag/retrieve-chunks-by-id
                        (fn [_docs _chunks id-list _opts]
                          (let [ids (mapv :chunk_id id-list)]
                            (swap! !read-calls conj ids)
                            (cond
                              (= ids ["d81ff032aa35" "a1a45b9d7585" "05e9fe2c2c5b"]) first-read
                              (= ids ["64cc1aee310d" "2f6cee8d5f5e" "f2c87d24c5be"]) second-read
                              :else [])))]
            (let [!workspace (workspace/create-workspace)
                  initial-messages (loop/build-initial-messages
                                    loop/default-system-prompt
                                    query
                                    [])
                  ambient-ctx {:docs-collection "test-docs"
                               :chunks-collection "test-chunks"
                               :phrases-collection "test-phrases"
                               :conversation-history []
                               :opts {:tenant "test"
                                      :environment "test"
                                      :read-signals-llm-fn default-read-signals-llm-fn}}
                  result (loop/agentic-loop initial-messages !workspace ambient-ctx
                                             {:max-iterations (:max-iterations fixture)
                                              :sufficiency-llm-fn nil})]
              {:result result
               :workspace @!workspace}))]
      (is (true? (:exhausted result)))
      (is (nil? (:error result)))
      (is (= final-response (:response result)))
      (is (= (:tool-sequence (:expected fixture))
             (trace-tool-sequence (:trace result))))
      (is (= [["d81ff032aa35" "a1a45b9d7585" "05e9fe2c2c5b"]
              ["64cc1aee310d" "2f6cee8d5f5e" "f2c87d24c5be"]
              ["d81ff032aa35" "a1a45b9d7585" "05e9fe2c2c5b"]]
             @!read-calls))
      (is (= final-response (:last-generated-response workspace)))
      (is (true? (:last-generate-insufficient-context workspace))))))

;; =============================================================================
;; Phase 1: Budget Profile Pairs
;; =============================================================================

(defn- make-search-budget-pivot-call-llm
  []
  (let [state (atom :search-1)]
    (fn [_tenant messages _tools _model _temperature & _opts]
      (let [last-content (or (:content (last messages)) "")
            exhausted? (search-budget-exhausted? last-content)]
        (case @state
          :search-1
          (do (reset! state :read-1)
              (tool-call-response
               "Jeg starter med et bredt sok."
               "search"
               {:queries ["utforte arsverk Digdir 2022"
                          "Digdir bemanning 2022"
                          "Digdir ansatte 2022"]}
               "call_s1"))

          :read-1
          (do (reset! state :rerank-1)
              (tool-call-response
               "Jeg leser det beste treffet."
               "read_chunks"
               {:chunk_ids ["c1"]}
               "call_r1"))

          :rerank-1
          (do (reset! state :generate-1)
              (tool-call-response
               "Jeg reranker den leste evidensen."
               "rerank_results"
               {:query "Hva var det offisielt oppgitte tallet for utforte arsverk i Digdir per 31.12.2022?"}
               "call_rr1"))

          :generate-1
          (do (reset! state :search-2)
              (tool-call-response
               "Jeg prover a svare."
               "generate_response"
               {:query "Hva var det offisielt oppgitte tallet for utforte arsverk i Digdir per 31.12.2022?"}
               "call_g1"))

          :search-2
          (do (reset! state :read-2)
              (tool-call-response
               "Jeg prover et mer presist sok."
               "search"
               {:queries ["Digdir arsrapport 2022 arsverk"
                          "Digdir oppgitt arsverk 2022"]}
               "call_s2"))

          :read-2
          (do (reset! state :rerank-2)
              (tool-call-response
               "Jeg leser den nye kandidaten."
               "read_chunks"
               {:chunk_ids ["c2"]}
               "call_r2"))

          :rerank-2
          (do (reset! state :generate-2)
              (tool-call-response
               "Jeg reranker pa nytt."
               "rerank_results"
               {:query "Hva var det offisielt oppgitte tallet for utforte arsverk i Digdir per 31.12.2022?"}
               "call_rr2"))

          :generate-2
          (do (reset! state :search-3)
              (tool-call-response
               "Jeg prover a svare igjen."
               "generate_response"
               {:query "Hva var det offisielt oppgitte tallet for utforte arsverk i Digdir per 31.12.2022?"}
               "call_g2"))

          :search-3
          (do (reset! state :read-3)
              (tool-call-response
               "Jeg snevrer inn mot hovedtall."
               "search"
               {:queries ["Digdir hovudtal 2022 arsverk"
                          "Digdir offisielt arsverk 2022"]}
               "call_s3"))

          :read-3
          (do (reset! state :rerank-3)
              (tool-call-response
               "Jeg leser den nye hovedtall-kandidaten."
               "read_chunks"
               {:chunk_ids ["c3"]}
               "call_r3"))

          :rerank-3
          (do (reset! state :generate-3)
              (tool-call-response
               "Jeg reranker igjen."
               "rerank_results"
               {:query "Hva var det offisielt oppgitte tallet for utforte arsverk i Digdir per 31.12.2022?"}
               "call_rr3"))

          :generate-3
          (do (reset! state :search-4)
              (tool-call-response
               "Jeg prover pa nytt."
               "generate_response"
               {:query "Hva var det offisielt oppgitte tallet for utforte arsverk i Digdir per 31.12.2022?"}
               "call_g3"))

          :search-4
          (do (reset! state :read-4)
              (tool-call-response
               "Jeg gjor et fjerde malrettet sok."
               "search"
               {:queries ["Digitaliseringsdirektoratet arsrapport 2022 hovudtal"
                          "Digdir 31.12.2022 utforte arsverk"]}
               "call_s4"))

          :read-4
          (do (reset! state :rerank-4)
              (tool-call-response
               "Jeg leser enda en kandidat."
               "read_chunks"
               {:chunk_ids ["c4"]}
               "call_r4"))

          :rerank-4
          (do (reset! state :generate-4)
              (tool-call-response
               "Jeg reranker pa nytt."
               "rerank_results"
               {:query "Hva var det offisielt oppgitte tallet for utforte arsverk i Digdir per 31.12.2022?"}
               "call_rr4"))

          :generate-4
          (do (reset! state :search-5-attempt)
              (tool-call-response
               "Jeg trenger ett siste sok mot arsrapport-tabellen."
               "generate_response"
               {:query "Hva var det offisielt oppgitte tallet for utforte arsverk i Digdir per 31.12.2022?"}
               "call_g4"))

          :search-5-attempt
          (do (reset! state :after-search-5)
              (tool-call-response
               "Jeg gjor ett siste, smalt sok."
               "search"
               {:queries ["Digdir arsrapport 2022 hovudtal utforte arsverk"]}
               "call_s5"))

          :after-search-5
          (do (reset! state (if exhausted? :final-failure :rerank-5))
              (if exhausted?
                (final-response
                 "Jeg fant fortsatt ikke det offisielt oppgitte tallet innenfor tilgjengelig budsjett.")
                (tool-call-response
                 "Jeg leser den avgjorende tabell-chunken."
                 "read_chunks"
                 {:chunk_ids ["c5"]}
                 "call_r5")))

          :read-5
          (do (reset! state :rerank-5)
              (tool-call-response
               "Jeg leser den avgjorende tabell-chunken."
               "read_chunks"
               {:chunk_ids ["c5"]}
               "call_r5"))

          :rerank-5
          (do (reset! state :generate-5)
              (tool-call-response
               "Jeg reranker med den avgjorende tabellen."
               "rerank_results"
               {:query "Hva var det offisielt oppgitte tallet for utforte arsverk i Digdir per 31.12.2022?"}
               "call_rr5"))

          :generate-5
          (do (reset! state :final-success)
              (tool-call-response
               "Na har jeg nok evidens til a svare."
               "generate_response"
               {:query "Hva var det offisielt oppgitte tallet for utforte arsverk i Digdir per 31.12.2022?"}
               "call_g5"))

          :final-success
          (do (reset! state :done)
              (final-response "Digdir hadde 326 utforte arsverk i 2022 [1]."))

          :final-failure
          (final-response
           "Jeg fant fortsatt ikke det offisielt oppgitte tallet innenfor tilgjengelig budsjett.")

          (throw (ex-info "Unexpected search-budget LLM state"
                          {:state @state :last-content last-content})))))))

(defn- run-search-budget-pair
  [budget-limits]
  (let [query "Hva var det offisielt oppgitte tallet for utforte arsverk i Digdir per 31.12.2022?"
        generic-metadata
        (fn [chunk-id title]
          {:chunk_id chunk-id
           :doc_num (str "d-" chunk-id)
           :chunk_index 0
           :content_length 80
           :metadata "{:header \"Bemanning\"}"
           :test-docs {:title title :total_chunks 1}})
        decisive-metadata
        {:chunk_id "c5"
         :doc_num "d-c5"
         :chunk_index 0
         :content_length 120
         :metadata "{:header \"Hovudtal\"}"
         :test-docs {:title "Arsrapport Digdir 2022" :total_chunks 1}}
        generic-read
        (fn [chunk-id content]
          [{:chunk_id chunk-id
            :doc_num (str "d-" chunk-id)
            :chunk_index 0
            :content_markdown content
            :content_length (count content)
            :metadata "{:header \"Bemanning\"}"
            :test-docs {:title "HR og bemanning" :total_chunks 1}}])
        decisive-read
        [{:chunk_id "c5"
          :doc_num "d-c5"
          :chunk_index 0
          :content_markdown "Digdir besto 31.12.2022 av 326 utforte arsverk."
          :content_length 49
          :metadata "{:header \"Hovudtal\"}"
          :test-docs {:title "Arsrapport Digdir 2022" :total_chunks 1}}]
        sub-skill-results
        {"retrieval_1" {:outputs {:chunks [(generic-metadata "c1" "HR og bemanning")]
                                  :search-attribution {:phrase 0 :metadata 3 :content 1}}}
         "rerank_1"    {:outputs {:reranked-chunks (generic-read "c1" "Generell bemanningsomtale uten eksakt arsverkstall.")
                                  :context-docs [{:page_content "Generell bemanningsomtale uten eksakt arsverkstall."
                                                  :metadata {:source "c1"}}]}}
         "synthesis_1" {:outputs {:response "Fant ikke et offisielt oppgitt tall."
                                  :insufficient-context true}}
         "retrieval_2" {:outputs {:chunks [(generic-metadata "c2" "Organisasjon og ansatte")]
                                  :search-attribution {:phrase 0 :metadata 4 :content 2}}}
         "rerank_2"    {:outputs {:reranked-chunks (generic-read "c2" "Kandidaten omtaler ansatte og rekruttering, men ikke eksakt utforte arsverk.")
                                  :context-docs [{:page_content "Kandidaten omtaler ansatte og rekruttering, men ikke eksakt utforte arsverk."
                                                  :metadata {:source "c2"}}]}}
         "synthesis_2" {:outputs {:response "Kildene peker fortsatt ikke pa et offisielt oppgitt tall."
                                  :insufficient-context true}}
         "retrieval_3" {:outputs {:chunks [(generic-metadata "c3" "Bemanning og utvikling")]
                                  :search-attribution {:phrase 0 :metadata 5 :content 3}}}
         "rerank_3"    {:outputs {:reranked-chunks (generic-read "c3" "Denne delen omtaler bemanning, men ikke hovedtallet for utforte arsverk.")
                                  :context-docs [{:page_content "Denne delen omtaler bemanning, men ikke hovedtallet for utforte arsverk."
                                                  :metadata {:source "c3"}}]}}
         "synthesis_3" {:outputs {:response "Jeg mangler fortsatt det kanoniske hovedtallet."
                                  :insufficient-context true}}
         "retrieval_4" {:outputs {:chunks [(generic-metadata "c4" "Arsrapport Digdir 2022 vedlegg")]
                                  :search-attribution {:phrase 0 :metadata 6 :content 4}}}
         "rerank_4"    {:outputs {:reranked-chunks (generic-read "c4" "Vedlegget omtaler stillinger og turnover, men ikke selve hovedtallet.")
                                  :context-docs [{:page_content "Vedlegget omtaler stillinger og turnover, men ikke selve hovedtallet."
                                                  :metadata {:source "c4"}}]}}
         "synthesis_4" {:outputs {:response "Jeg mangler fortsatt den avgjorende tabellen."
                                  :insufficient-context true}}
         "retrieval_5" {:outputs {:chunks [decisive-metadata]
                                  :search-attribution {:phrase 0 :metadata 8 :content 7}}}
         "rerank_5"    {:outputs {:reranked-chunks decisive-read
                                  :context-docs [{:page_content (:content_markdown (first decisive-read))
                                                  :metadata {:source "c5"}}]}}
         "synthesis_5" {:outputs {:response "Digdir hadde 326 utforte arsverk i 2022 [1]."}}}
        retrieve-by-id
        (fn [_docs _chunks id-list _opts]
          (let [ids (mapv :chunk_id id-list)]
            (case (first ids)
              "c1" (generic-read "c1" "Generell bemanningsomtale uten eksakt arsverkstall.")
              "c2" (generic-read "c2" "Kandidaten omtaler ansatte og rekruttering, men ikke eksakt utforte arsverk.")
              "c3" (generic-read "c3" "Denne delen omtaler bemanning, men ikke hovedtallet for utforte arsverk.")
              "c4" (generic-read "c4" "Vedlegget omtaler stillinger og turnover, men ikke selve hovedtallet.")
              "c5" decisive-read
              [])))]
    (run-budgeted-deep
     {:query query
      :max-iterations 24
      :budget-limits budget-limits
      :call-llm (make-search-budget-pivot-call-llm)
      :execute-sub-skill (make-scripted-execute-sub-skill sub-skill-results)
      :retrieve-by-id retrieve-by-id})))

(defn- make-read-char-budget-call-llm
  []
  (let [state (atom :search)]
    (fn [_tenant messages _tools _model _temperature & _opts]
      (let [last-content (or (:content (last messages)) "")
            exhausted? (read-budget-exhausted? last-content)]
        (case @state
          :search
          (do (reset! state :read-turnover)
              (tool-call-response
               "Jeg starter med et bredt sok i arsrapporten."
               "search"
               {:queries ["Digdir hovudtal 2022 arsverk"
                          "Digdir bemanning 2022 arsrapport"
                          "Digdir tabell arsverk 2022"]}
               "call_s1"))

          :read-turnover
          (do (reset! state :read-range-1)
              (tool-call-response
               "Jeg leser toppkandidaten for kontekst."
               "read_chunks"
               {:chunk_ids ["turnover"]}
               "call_r1"))

          :read-range-1
          (do (reset! state :read-range-2)
              (tool-call-response
               "Jeg leser et bredt intervall rundt turnover-delen."
               "read_chunks"
               {:doc_num "33169" :chunk_range {:from 160 :to 170}}
               "call_r2"))

          :read-range-2
          (do (reset! state :read-range-3)
              (tool-call-response
               "Jeg leser et annet bredt intervall i samme dokument."
               "read_chunks"
               {:doc_num "33169" :chunk_range {:from 120 :to 130}}
               "call_r3"))

          :read-range-3
          (do (reset! state :read-table-attempt)
              (tool-call-response
               "Jeg leser enda et bredt intervall for sikkerhets skyld."
               "read_chunks"
               {:doc_num "33169" :chunk_range {:from 80 :to 90}}
               "call_r4"))

          :read-table-attempt
          (do (reset! state :after-table-attempt)
              (tool-call-response
               "Na leser jeg tabell-chunken med hovedtall."
               "read_chunks"
               {:chunk_ids ["table"]}
               "call_r5"))

          :after-table-attempt
          (do (reset! state (if exhausted? :final-failure :rerank))
              (if exhausted?
                (final-response
                 "Jeg fikk ikke lest hovudtal-tabellen innenfor dagens lese-budsjett.")
                (tool-call-response
                 "Jeg reranker den leste evidensen."
                 "rerank_results"
                 {:query "Hva sier hovudtal-tabellen i arsrapporten om avtalte og utforte arsverk i Digdir i 2022?"}
                 "call_rr1")))

          :rerank
          (do (reset! state :generate)
              (tool-call-response
               "Na har jeg nok evidens til a svare."
               "generate_response"
               {:query "Hva sier hovudtal-tabellen i arsrapporten om avtalte og utforte arsverk i Digdir i 2022?"}
               "call_g1"))

          :generate
          (do (reset! state :final-success)
              (final-response
               "Digdir hadde 356 avtalte arsverk og 326 utforte arsverk i 2022 [1]."))

          :final-success
          (do (reset! state :done)
              (final-response
               "Digdir hadde 356 avtalte arsverk og 326 utforte arsverk i 2022 [1]."))

          :final-failure
          (final-response
           "Jeg fikk ikke lest hovudtal-tabellen innenfor dagens lese-budsjett.")

          (throw (ex-info "Unexpected read-budget LLM state"
                          {:state @state :last-content last-content})))))))

(defn- run-read-char-budget-pair
  [budget-limits]
  (let [query "Hva sier hovudtal-tabellen i arsrapporten om avtalte og utforte arsverk i Digdir i 2022?"
        annual-report-title "Arsrapport Digitaliseringsdirektoratet 2022"
        broad-metadata [{:chunk_id "turnover"
                         :doc_num "33169"
                         :chunk_index 168
                         :content_length 636
                         :metadata "{\"Header 1\" \"Turnover\"}"
                         :test-docs {:title annual-report-title :total_chunks 220}}
                        {:chunk_id "nearby-a"
                         :doc_num "33169"
                         :chunk_index 167
                         :content_length 1100
                         :metadata "{\"Header 1\" \"Likestilling\"}"
                         :test-docs {:title annual-report-title :total_chunks 220}}
                        {:chunk_id "nearby-b"
                         :doc_num "33169"
                         :chunk_index 169
                         :content_length 1100
                         :metadata "{\"Header 1\" \"Forklaring\"}"
                         :test-docs {:title annual-report-title :total_chunks 220}}
                        {:chunk_id "nearby-c"
                         :doc_num "33169"
                         :chunk_index 128
                         :content_length 1100
                         :metadata "{\"Header 1\" \"Notat\"}"
                         :test-docs {:title annual-report-title :total_chunks 220}}
                        {:chunk_id "nearby-d"
                         :doc_num "33169"
                         :chunk_index 88
                         :content_length 1100
                         :metadata "{\"Header 1\" \"Bakgrunn\"}"
                         :test-docs {:title annual-report-title :total_chunks 220}}
                        {:chunk_id "table"
                         :doc_num "33169"
                         :chunk_index 20
                         :content_length 3000
                         :metadata "{\"Header 1\" \"Hovudtal\"}"
                         :test-docs {:title annual-report-title :total_chunks 220}}]
        turnover-read [{:chunk_id "turnover"
                        :doc_num "33169"
                        :chunk_index 168
                        :content_markdown "Turnovertallet for Digdir i 2022 er beregnet ut fra antall ansatte pr. 31.12.2022."
                        :content_length 636
                        :metadata "{\"Header 1\" \"Turnover\"}"
                        :test-docs {:title annual-report-title :total_chunks 220}}]
        table-read [{:chunk_id "table"
                     :doc_num "33169"
                     :chunk_index 20
                     :content_markdown "| Tal avtalte arsverk | 356 |\n| Tal utforte arsverk | 326 |"
                     :content_length 3000
                     :metadata "{\"Header 1\" \"Hovudtal\"}"
                     :test-docs {:title annual-report-title :total_chunks 220}}]
        sub-skill-results
        {"retrieval_1" {:outputs {:chunks broad-metadata
                                  :search-attribution {:phrase 0 :metadata 6 :content 4}}}
         "rerank_1"    {:outputs {:reranked-chunks table-read
                                  :context-docs [{:page_content (:content_markdown (first table-read))
                                                  :metadata {:source "table"}}]}}
         "synthesis_1" {:outputs {:response "Digdir hadde 356 avtalte arsverk og 326 utforte arsverk i 2022 [1]."}}}
        !range-calls (atom [])
        retrieve-by-id
        (fn [_docs _chunks id-list _opts]
          (let [ids (mapv :chunk_id id-list)]
            (case (first ids)
              "turnover" turnover-read
              "table" table-read
              [])))
        retrieve-by-range
        (fn [_docs _chunks doc-num from to _opts]
          (swap! !range-calls conj {:doc-num doc-num :from from :to to})
          (mapv (fn [idx]
                  {:chunk_id (str "range-" idx)
                   :doc_num doc-num
                   :chunk_index idx
                   :content_markdown (apply str (repeat 1334 "x"))
                   :content_length 1334
                   :metadata "{}"
                   :test-docs {:title annual-report-title :total_chunks 220}})
                (range from (inc to))))
        run-res
        (run-budgeted-deep
         {:query query
          :max-iterations 12
          :budget-limits budget-limits
          :call-llm (make-read-char-budget-call-llm)
          :execute-sub-skill (make-scripted-execute-sub-skill sub-skill-results)
          :retrieve-by-id retrieve-by-id
          :retrieve-by-range retrieve-by-range})]
    (assoc run-res :range-calls @!range-calls)))

(deftest search-budget-pair-current-defaults-stop-before-final-canonical-pivot
  (testing "Current search budget fails before the fifth canonical-source pivot, while recording explicit budget exhaustion"
    (let [{:keys [result workspace]} (run-search-budget-pair {:max-search-passes 4
                                                              :max-read-operations 6
                                                              :max-read-content-length 12000})]
      (is (some? (:response result)))
      (is (not (re-find #"326" (:response result))))
      (is (= 4 (count (:search-history workspace))))
      (is (= 4 (count (:read-history workspace))))
      (is (= ["search" "read_chunks" "rerank_results" "generate_response"
              "search" "read_chunks" "rerank_results" "generate_response"
              "search" "read_chunks" "rerank_results" "generate_response"
              "search" "read_chunks" "rerank_results" "generate_response"
              "search"]
             (trace-tool-sequence (:trace result))))
      (is (search-budget-exhausted?
           (get-in result [:trace 16 :tool-calls 0 :result-summary])))
      (is (= [:insufficient :insufficient :insufficient :insufficient]
             (mapv :status (:sufficiency-decisions workspace))))
      (is (= 4 (get-in workspace [:budget-limits :max-search-passes])))
      (is (= :re-search
             (get-in workspace [:sufficiency-decisions 3 :insufficiency :recommended-action]))))))

(deftest search-budget-pair-relaxed-defaults-allow-final-canonical-pivot
  (testing "A one-pass search budget increase unlocks the final annual-report pivot and grounded answer"
    (let [{:keys [result workspace]} (run-search-budget-pair {:max-search-passes 5
                                                              :max-read-operations 6
                                                              :max-read-content-length 12000})]
      (is (re-find #"326" (:response result)))
      (is (= 5 (count (:search-history workspace))))
      (is (= 5 (count (:read-history workspace))))
      (is (= [:insufficient :insufficient :insufficient :insufficient :conflicting]
             (mapv :status (:sufficiency-decisions workspace))))
      (is (= ["search" "read_chunks" "rerank_results" "generate_response"
              "search" "read_chunks" "rerank_results" "generate_response"
              "search" "read_chunks" "rerank_results" "generate_response"
              "search" "read_chunks" "rerank_results" "generate_response"
              "search" "read_chunks" "rerank_results" "generate_response"]
             (trace-tool-sequence (:trace result))))
      (is (= "c5" (-> workspace :read-history last :returned-chunk-ids first))))))

(deftest read-char-budget-pair-current-defaults-block-final-table-read
  (testing "Current read-char budget is exhausted by broad range reads before the decisive table chunk can be fetched"
    (let [{:keys [result workspace range-calls]} (run-read-char-budget-pair {:max-search-passes 4
                                                                              :max-read-operations 6
                                                                              :max-read-content-length 12000})]
      (is (some? (:response result)))
      (is (not (re-find #"326" (:response result))))
      (is (= 4 (count (:read-history workspace))))
      (is (= 11308 (:read-content-length workspace)))
      (is (= [{:doc-num "33169" :from 166 :to 168}
              {:doc-num "33169" :from 127 :to 129}
              {:doc-num "33169" :from 87 :to 89}]
             range-calls))
      (is (re-find #"Read budget exhausted"
                   (get-in result [:trace 5 :tool-calls 0 :result-summary]))))))

(deftest read-char-budget-pair-relaxed-defaults-allow-final-table-read
  (testing "A modest read-char budget increase leaves enough room for the decisive hovudtal table chunk"
    (let [{:keys [result workspace range-calls]} (run-read-char-budget-pair {:max-search-passes 4
                                                                              :max-read-operations 6
                                                                              :max-read-content-length 16000})]
      (is (re-find #"326" (:response result)))
      (is (= 5 (count (:read-history workspace))))
      (is (= 15642 (:read-content-length workspace)))
      (is (= ["table"] (-> workspace :read-history last :returned-chunk-ids)))
      (is (= [{:doc-num "33169" :from 166 :to 168}
              {:doc-num "33169" :from 127 :to 129}
              {:doc-num "33169" :from 87 :to 89}]
             range-calls))
      (is (= [:insufficient]
             (mapv :status (:sufficiency-decisions workspace)))))))

;; =============================================================================
;; Exploratory Phase 2 Coverage
;;
;; These cases exercise additional budget dimensions and the paired-budget
;; harness using synthetic baselines tighter than production defaults (4/6/12000).
;;
;; They serve as regression gates for the budget-exhaustion logic itself,
;; ensuring the agent correctly reacts to limits and recommends the appropriate
;; uncertainty actions, without claiming the production profile is insufficient
;; for these specific queries.
;; =============================================================================

(defn- make-read-ops-budget-call-llm
  []
  (let [state (atom :search)]
    (fn [_tenant messages _tools _model _temperature & _opts]
      (let [last-content (or (:content (last messages)) "")
            exhausted? (read-budget-exhausted? last-content)]
        (case @state
          :search
          (do (reset! state :read-avtalte)
              (tool-call-response
               "Jeg starter med et sok etter arsverkstall og forklaring."
               "search"
               {:queries ["Digdir avtalte utforte arsverk 2022"
                          "Digdir hovudtal arsverk 2022"
                          "Digdir forklaring avtalte utforte arsverk 2022"]}
               "call_s1"))

          :read-avtalte
          (do (reset! state :read-utforte)
              (tool-call-response
               "Jeg leser chunken med avtalte arsverk."
               "read_chunks"
               {:chunk_ids ["avtalte"]}
               "call_r1"))

          :read-utforte
          (do (reset! state :read-forklaring-attempt)
              (tool-call-response
               "Jeg leser chunken med utforte arsverk."
               "read_chunks"
               {:chunk_ids ["utforte"]}
               "call_r2"))

          :read-forklaring-attempt
          (do (reset! state :after-third-read-attempt)
              (tool-call-response
               "Jeg leser forklaringschunken for a kunne sammenligne riktig."
               "read_chunks"
               {:chunk_ids ["forklaring"]}
               "call_r3"))

          :after-third-read-attempt
          (do (reset! state (if exhausted? :final-failure :rerank))
              (if exhausted?
                (final-response
                 "Jeg fikk ikke lest nok kilder til a sammenligne avtalte og utforte arsverk innenfor lese-budsjettet.")
                (tool-call-response
                 "Jeg reranker den samlede evidensen."
                 "rerank_results"
                 {:query "Sammenlign avtalte og utforte arsverk i Digdir i 2022, og forklar forskjellen kort."}
                 "call_rr1")))

          :rerank
          (do (reset! state :generate)
              (tool-call-response
               "Na har jeg nok evidens til a svare."
               "generate_response"
               {:query "Sammenlign avtalte og utforte arsverk i Digdir i 2022, og forklar forskjellen kort."}
               "call_g1"))

          :generate
          (do (reset! state :final-success)
              (final-response
               "Digdir hadde 356 avtalte arsverk og 326 utforte arsverk i 2022 [1]. Forskjellen skyldes at avtalte arsverk beskriver planlagt bemanning, mens utforte arsverk viser faktisk arbeid i perioden."))

          :final-success
          (do (reset! state :done)
              (final-response
               "Digdir hadde 356 avtalte arsverk og 326 utforte arsverk i 2022 [1]. Forskjellen skyldes at avtalte arsverk beskriver planlagt bemanning, mens utforte arsverk viser faktisk arbeid i perioden."))

          :final-failure
          (final-response
           "Jeg fikk ikke lest nok kilder til a sammenligne avtalte og utforte arsverk innenfor lese-budsjettet.")

          (throw (ex-info "Unexpected read-ops LLM state"
                          {:state @state :last-content last-content})))))))

(defn- run-read-ops-budget-pair
  [budget-limits]
  (let [query "Sammenlign avtalte og utforte arsverk i Digdir i 2022, og forklar forskjellen kort."
        metadata-for
        (fn [chunk-id header]
          {:chunk_id chunk-id
           :doc_num "d-ops"
           :chunk_index 0
           :content_length 180
           :metadata (str "{:header \"" header "\"}")
           :test-docs {:title "Arsrapport Digdir 2022" :total_chunks 3}})
        avtalte-read
        [{:chunk_id "avtalte"
          :doc_num "d-ops"
          :chunk_index 0
          :content_markdown "| Tal avtalte arsverk | 356 |"
          :content_length 29
          :metadata "{:header \"Hovudtal\"}"
          :test-docs {:title "Arsrapport Digdir 2022" :total_chunks 3}}]
        utforte-read
        [{:chunk_id "utforte"
          :doc_num "d-ops"
          :chunk_index 1
          :content_markdown "| Tal utforte arsverk | 326 |"
          :content_length 29
          :metadata "{:header \"Hovudtal\"}"
          :test-docs {:title "Arsrapport Digdir 2022" :total_chunks 3}}]
        forklaring-read
        [{:chunk_id "forklaring"
          :doc_num "d-ops"
          :chunk_index 2
          :content_markdown "Avtalte arsverk is budsjettert bemanning, mens utforte arsverk viser faktisk arbeid gjennom aret."
          :content_length 98
          :metadata "{:header \"Forklaring\"}"
          :test-docs {:title "Arsrapport Digdir 2022" :total_chunks 3}}]
        reranked-chunks (vec (concat avtalte-read utforte-read forklaring-read))
        sub-skill-results
        {"retrieval_1" {:outputs {:chunks [(metadata-for "avtalte" "Hovudtal")
                                          (metadata-for "utforte" "Hovudtal")
                                          (metadata-for "forklaring" "Forklaring")]
                                  :search-attribution {:phrase 0 :metadata 5 :content 4}}}
         "rerank_1"    {:outputs {:reranked-chunks reranked-chunks
                                  :context-docs (mapv (fn [chunk]
                                                        {:page_content (:content_markdown chunk)
                                                         :metadata {:source (:chunk_id chunk)}})
                                                      reranked-chunks)}}
         "synthesis_1" {:outputs {:response "Digdir hadde 356 avtalte arsverk og 326 utforte arsverk i 2022 [1]. Forskjellen skyldes at avtalte arsverk beskriver planlagt bemanning, mens utforte arsverk viser faktisk arbeid i perioden."}}}
        retrieve-by-id
        (fn [_docs _chunks id-list _opts]
          (let [ids (mapv :chunk_id id-list)]
            (case (first ids)
              "avtalte" avtalte-read
              "utforte" utforte-read
              "forklaring" forklaring-read
              [])))]
    (run-budgeted-deep
     {:query query
      :max-iterations 12
      :budget-limits budget-limits
      :call-llm (make-read-ops-budget-call-llm)
      :execute-sub-skill (make-scripted-execute-sub-skill sub-skill-results)
      :retrieve-by-id retrieve-by-id})))

(defn- make-conflict-canonical-pivot-call-llm
  []
  (let [state (atom :search-1)]
    (fn [_tenant messages _tools _model _temperature & _opts]
      (let [last-content (or (:content (last messages)) "")
            exhausted? (search-budget-exhausted? last-content)]
        (case @state
          :search-1
          (do (reset! state :read-avtalte)
              (tool-call-response
               "Jeg starter med et sok etter det offisielle arsverkstallet."
               "search"
               {:queries ["offisielt arsverkstall Digdir 2022"
                          "Digdir arsverk 2022"
                          "Digdir hovudtal 2022"]}
               "call_s1"))

          :read-avtalte
          (do (reset! state :read-utforte)
              (tool-call-response
               "Jeg leser chunken som nevner avtalte arsverk."
               "read_chunks"
               {:chunk_ids ["avtalte"]}
               "call_r1"))

          :read-utforte
          (do (reset! state :rerank-1)
              (tool-call-response
               "Jeg leser chunken som nevner utforte arsverk."
               "read_chunks"
               {:chunk_ids ["utforte"]}
               "call_r2"))

          :rerank-1
          (do (reset! state :generate-1)
              (tool-call-response
               "Jeg reranker den konfliktende evidensen."
               "rerank_results"
               {:query "Hva er det offisielle tallet for arsverk i Digdir i 2022?"}
               "call_rr1"))

          :generate-1
          (do (reset! state :search-2-attempt)
              (tool-call-response
               "Jeg prover a svare, men ma kanskje pivoteres til en kanonisk kilde."
               "generate_response"
               {:query "Hva er det offisielle tallet for arsverk i Digdir i 2022?"}
               "call_g1"))

          :search-2-attempt
          (do (reset! state :after-search-2)
              (tool-call-response
               "Jeg gjor et nytt sok mot kanonisk oppsummering."
               "search"
               {:queries ["Digdir arsrapport 2022 hovudtal utforte arsverk"
                          "Digdir offisielt oppgitt utforte arsverk 2022"]}
               "call_s2"))

          :after-search-2
          (do (reset! state (if exhausted? :final-failure :rerank-2))
              (if exhausted?
                (final-response
                 "Jeg sto igjen med motstridende tall og fikk ikke gjort et nytt sok mot den kanoniske kilden.")
                (tool-call-response
                 "Jeg leser den kanoniske hovudtal-chunken."
                 "read_chunks"
                 {:chunk_ids ["canonical"]}
                 "call_r3")))

          :read-canonical
          (do (reset! state :rerank-2)
              (tool-call-response
               "Jeg leser den kanoniske hovudtal-chunken."
               "read_chunks"
               {:chunk_ids ["canonical"]}
               "call_r3"))

          :rerank-2
          (do (reset! state :generate-2)
              (tool-call-response
               "Jeg reranker pa nytt med den kanoniske evidensen."
               "rerank_results"
               {:query "Hva er det offisielle tallet for arsverk i Digdir i 2022?"}
               "call_rr2"))

          :generate-2
          (do (reset! state :final-success)
              (tool-call-response
               "Na kan jeg svare med riktig scope."
               "generate_response"
               {:query "Hva er det offisielle tallet for arsverk i Digdir i 2022?"}
               "call_g2"))

          :final-success
          (do (reset! state :done)
              (final-response
               "Det offisielt oppgitte tallet for utforte arsverk i Digdir i 2022 var 326 [1]."))

          :final-failure
          (final-response
           "Jeg sto igjen med motstridende tall og fikk ikke gjort et nytt sok mot den kanoniske kilden.")

          (throw (ex-info "Unexpected conflict-pivot LLM state"
                          {:state @state :last-content last-content})))))))

(defn- run-conflict-canonical-pivot-pair
  [budget-limits]
  (let [query "Hva er det offisielle tallet for arsverk i Digdir i 2022?"
        generic-metadata
        (fn [chunk-id title header]
          {:chunk_id chunk-id
           :doc_num (str "d-" chunk-id)
           :chunk_index 0
           :content_length 120
           :metadata (str "{:header \"" header "\"}")
           :test-docs {:title title :total_chunks 1}})
        avtalte-read
        [{:chunk_id "avtalte"
          :doc_num "d-avtalte"
          :chunk_index 0
          :content_markdown "Digdir hadde 356 avtalte arsverk i 2022."
          :content_length 39
          :metadata "{:header \"Hovudtal\"}"
          :test-docs {:title "Arsrapport Digdir 2022 vedlegg" :total_chunks 1}}]
        utforte-read
        [{:chunk_id "utforte"
          :doc_num "d-utforte"
          :chunk_index 0
          :content_markdown "Digdir hadde 326 utforte arsverk i 2022."
          :content_length 39
          :metadata "{:header \"Hovudtal\"}"
          :test-docs {:title "Arsrapport Digdir 2022 bemanning" :total_chunks 1}}]
        canonical-read
        [{:chunk_id "canonical"
          :doc_num "d-canonical"
          :chunk_index 0
          :content_markdown "Tal utforte arsverk 2022: 326."
          :content_length 30
          :metadata "{:header \"Hovudtal\"}"
          :test-docs {:title "Arsrapport Digdir 2022" :total_chunks 1}}]
        sub-skill-results
        {"retrieval_1" {:outputs {:chunks [(generic-metadata "avtalte" "Arsrapport Digdir 2022 vedlegg" "Hovudtal")
                                          (generic-metadata "utforte" "Arsrapport Digdir 2022 bemanning" "Hovudtal")]
                                  :search-attribution {:phrase 0 :metadata 4 :content 3}}}
         "rerank_1"    {:outputs {:reranked-chunks (vec (concat avtalte-read utforte-read))
                                  :context-docs [{:page_content (:content_markdown (first avtalte-read))
                                                  :metadata {:source "avtalte"}}
                                                 {:page_content (:content_markdown (first utforte-read))
                                                  :metadata {:source "utforte"}}]}}
         "synthesis_1" {:outputs {:response "Kildene inneholder motstridende tall: 356 avtalte arsverk og 326 utforte arsverk. Jeg kan ikke presentere ett tall som definitivt uten en kanonisk oppsummering."
                                  :insufficient-context true}}
         "retrieval_2" {:outputs {:chunks [(generic-metadata "canonical" "Arsrapport Digdir 2022" "Hovudtal")]
                                  :search-attribution {:phrase 0 :metadata 6 :content 6}}}
         "rerank_2"    {:outputs {:reranked-chunks canonical-read
                                  :context-docs [{:page_content (:content_markdown (first canonical-read))
                                                  :metadata {:source "canonical"}}]}}
         "synthesis_2" {:outputs {:response "Det offisielt oppgitte tallet for utforte arsverk i Digdir i 2022 var 326 [1]."}}}
        retrieve-by-id
        (fn [_docs _chunks id-list _opts]
          (let [ids (mapv :chunk_id id-list)]
            (case (first ids)
              "avtalte" avtalte-read
              "utforte" utforte-read
              "canonical" canonical-read
              [])))]
    (run-budgeted-deep
     {:query query
      :max-iterations 16
      :budget-limits budget-limits
      :call-llm (make-conflict-canonical-pivot-call-llm)
      :execute-sub-skill (make-scripted-execute-sub-skill sub-skill-results)
      :retrieve-by-id retrieve-by-id})))

(deftest read-ops-budget-pair-current-defaults-block-third-targeted-read
  (testing "Current read-op budget blocks the explanatory third read needed for a grounded comparison"
    (let [{:keys [result workspace]} (run-read-ops-budget-pair {:max-search-passes 4
                                                                :max-read-operations 2
                                                                :max-read-content-length 12000})]
      (is (some? (:response result)))
      (is (not (re-find #"326" (:response result))))
      (is (= 1 (count (:search-history workspace))))
      (is (= 2 (count (:read-history workspace))))
      (is (= 58 (:read-content-length workspace)))
      (is (= ["search" "read_chunks" "read_chunks" "read_chunks"]
             (trace-tool-sequence (:trace result))))
      (is (re-find #"Read budget exhausted"
                   (get-in result [:trace 3 :tool-calls 0 :result-summary]))))))

(deftest read-ops-budget-pair-relaxed-defaults-allow-grounded-comparison
  (testing "A one-read-op increase unlocks the explanation read and final grounded comparison"
    (let [{:keys [result workspace]} (run-read-ops-budget-pair {:max-search-passes 4
                                                                :max-read-operations 3
                                                                :max-read-content-length 12000})]
      (is (re-find #"356 avtalte arsverk og 326 utforte arsverk" (:response result)))
      (is (re-find #"faktisk arbeid" (:response result)))
      (is (= 1 (count (:search-history workspace))))
      (is (= 3 (count (:read-history workspace))))
      (is (= 156 (:read-content-length workspace)))
      (is (contains? #{["search" "read_chunks" "read_chunks" "read_chunks" "rerank_results" "generate_response"]
                       ["search" "read_chunks" "read_chunks" "read_chunks" "generate_response"]}
                     (trace-tool-sequence (:trace result))))
      (is (contains? #{1 2 3} (count (:sufficiency-decisions workspace)))))))

(deftest conflict-canonical-pivot-current-defaults-stop-after-conflict
  (testing "Current search budget leaves the run stuck after a conflict without room for a canonical-source pivot"
    (let [{:keys [result workspace]} (run-conflict-canonical-pivot-pair {:max-search-passes 1
                                                                         :max-read-operations 6
                                                                         :max-read-content-length 12000})]
      (is (some? (:response result)))
      (is (not (re-find #"326 \\[1\\]" (:response result))))
      (is (= 1 (count (:search-history workspace))))
      (is (= 2 (count (:read-history workspace))))
      (is (= [:conflicting]
             (mapv :status (:sufficiency-decisions workspace))))
      (is (= :re-search
             (get-in workspace [:sufficiency-decisions 0 :action])))
      (is (re-find #"conflicting values"
                   (get-in result [:trace 4 :tool-calls 0 :result-summary])))
      (is (sufficiency-gate-rejected?
           (get-in result [:trace 4 :tool-calls 0 :result-summary])))
      (is (search-budget-exhausted?
           (get-in result [:trace 5 :tool-calls 0 :result-summary]))))))

(deftest conflict-canonical-pivot-relaxed-defaults-reach-canonical-summary
  (testing "A one-pass search increase allows the canonical annual-report pivot to resolve the conflict"
    (let [{:keys [result workspace]} (run-conflict-canonical-pivot-pair {:max-search-passes 2
                                                                         :max-read-operations 6
                                                                         :max-read-content-length 12000})]
      (is (re-find #"offisielt oppgitte tallet.*326" (:response result)))
      (is (= 2 (count (:search-history workspace))))
      (is (= 3 (count (:read-history workspace))))
      (is (= [:conflicting :conflicting]
             (mapv :status (:sufficiency-decisions workspace))))
      (is (re-find #"conflicting values"
                   (get-in result [:trace 4 :tool-calls 0 :result-summary])))
      (is (= ["search" "read_chunks" "read_chunks" "rerank_results" "generate_response"
              "search" "read_chunks" "rerank_results" "generate_response"]
             (trace-tool-sequence (:trace result))))
      (is (= "canonical" (-> workspace :read-history last :returned-chunk-ids first))))))
