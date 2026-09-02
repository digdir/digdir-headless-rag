(ns digdir.skills.builtin.agent-test
  "Unit tests for the agent skill.

   Tests cover metadata validation, workspace management,
   tool result formatting, tool definitions, and registration.
   No LLM calls are made."
  (:require [digdir.test-utils :as tu]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.skills.builtin.agent.tools :as tools]
            [digdir.skills.builtin.agent.read-signals :as read-signals]
            [digdir.skills.builtin.agent.sufficiency :as sufficiency]
            [digdir.skills.builtin.agent.loop :as loop]
            [digdir.skills.builtin.agent.core :as core]
            [digdir.skills.context :as ctx]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.core :as rag]
            [digdir.skills.api :as api]
            [digdir.skills.test-helpers :as th]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn with-initialized-skills [f]
  (api/reset-skills!)
  (api/initialize!)
  (f)
  (api/reset-skills!))

(use-fixtures :each with-initialized-skills)

;; =============================================================================
;; Metadata Tests
;; =============================================================================

(deftest test-agent-metadata-valid
  (testing "Agent skill metadata passes Malli validation"
    (is (skills/valid-skill-metadata? core/agent-metadata))))

(deftest test-agent-metadata-fields
  (testing "Agent skill has correct metadata fields"
    (is (= :builtin/agent (:skill-id core/agent-metadata)))
    (is (= :orchestration (:category core/agent-metadata)))
    (is (= [:query :docs-collection :chunks-collection
            :phrases-collection :conversation-history]
           (:inputs core/agent-metadata)))
    (is (= [:response :clarification-request :trace :chunks :queries]
           (take 5 (:outputs core/agent-metadata))))
    (is (contains? (set (:outputs core/agent-metadata)) :insufficient-context))
    (is (contains? (set (:outputs core/agent-metadata)) :search-errors))
    (is (contains? (set (:outputs core/agent-metadata)) :backend-issues))
    (is (contains? (set (:outputs core/agent-metadata)) :last-response-validation-insufficiency))
    (is (contains? (:parameters core/agent-metadata) :model))
    (is (contains? (:parameters core/agent-metadata) :temperature))
    (is (contains? (:parameters core/agent-metadata) :max-iterations))
    (is (contains? (:parameters core/agent-metadata) :max-search-passes))
    (is (contains? (:parameters core/agent-metadata) :max-read-operations))
    (is (contains? (:parameters core/agent-metadata) :max-read-content-length))
    (is (contains? (:parameters core/agent-metadata) :system-prompt))
    (is (nil? (:required-services core/agent-metadata)))))

;; =============================================================================
;; Workspace Tests
;; =============================================================================

(deftest test-create-workspace
  (testing "Creates a fresh workspace with expected keys"
    (let [!ws (workspace/create-workspace)]
      (is (map? @!ws))
      (is (= {} (:chunks @!ws)))
      (is (= #{} (:seen-search-chunk-ids @!ws)))
      (is (= [] (:queries @!ws)))
      (is (= [] (:search-history @!ws)))
      (is (= [] (:search-errors @!ws)))
      (is (= [] (:read-history @!ws)))
      (is (= [] (:stage-timings @!ws)))
      (is (= 0 (:read-content-length @!ws)))
      (is (nil? (:budget-limits @!ws)))
      (is (= [] (:reranked-chunks @!ws)))
      (is (= [] (:context-docs @!ws)))
      (is (= [] (:iteration-history @!ws)))
      (is (nil? (:citation-validation @!ws))))))

(deftest test-record-stage-timing
  (testing "Records timing entries in the workspace"
    (let [!ws (workspace/create-workspace)]
      (workspace/record-stage-timing!
       !ws
       {:stage :agent-llm
        :iteration 0
        :duration-ms 17
        :status :ok
        :tool "turn"})
      ;; Asserted as a SUBSET of one entry rather than as an exact vector of
      ;; exact maps. Whole-map equality here goes red on any correct ADDED key
      ;; — it did when :usage-expected? started being derived at the recorder —
      ;; which is an anti-guard: a check whose passing condition the right fix
      ;; violates. The fields below are the invariant; the map shape is not.
      (is (= 1 (count (:stage-timings @!ws))))
      (is (= {:stage :agent-llm
              :iteration 0
              :duration-ms 17
              :status :ok
              :tool "turn"}
             (select-keys (first (:stage-timings @!ws))
                          [:stage :iteration :duration-ms :status :tool])))
      (is (true? (:usage-expected? (first (:stage-timings @!ws))))
          ":agent-llm is LLM-backed, so the recorder derives the claim from the
           stage keyword — the caller did not pass it"))))

(deftest test-add-chunks-deduplication
  (testing "Adds chunks and deduplicates by chunk_id"
    (let [!ws (workspace/create-workspace)
          chunk-a {:chunk_id "a" :content_markdown "Content A"}
          chunk-b {:chunk_id "b" :content_markdown "Content B"}
          chunk-a2 {:chunk_id "a" :content_markdown "Content A updated"}]

      ;; Add first batch
      (let [new-count (workspace/add-chunks-to-workspace! !ws [chunk-a chunk-b])]
        (is (= 2 new-count))
        (is (= 2 (count (:chunks @!ws)))))

      ;; Add with duplicate
      (let [new-count (workspace/add-chunks-to-workspace! !ws [chunk-a2 chunk-b])]
        (is (= 0 new-count) "Duplicates should not be added")
        (is (= 2 (count (:chunks @!ws))) "Count should remain the same")))))

(deftest test-get-workspace-chunks
  (testing "Returns all workspace chunks as a vector"
    (let [!ws (workspace/create-workspace)]
      (workspace/add-chunks-to-workspace! !ws [{:chunk_id "a" :content_markdown "A"}
                                            {:chunk_id "b" :content_markdown "B"}])
      (let [chunks (workspace/get-workspace-chunks !ws)]
        (is (vector? chunks))
        (is (= 2 (count chunks)))))))

(deftest test-refinement-suggestions-stay-corpus-grounded
  (testing "next-research-suggestions surfaces the planner's UN-searched corpus phrases, not just generic variants"
    (let [planner-phrases ["altinn 3 juni 2020" "første versjon av altinn 3" "altinn 3"
                           "altinn 3 overgangs tjenester" "altinn 3 overførte tjenester"
                           "altinn 3 formidling" "altinn 3 melding"
                           "synkronisering av statusendringer mellom altinn 2 og 3"]
          searched (vec (take 6 planner-phrases))   ; the take-6 batch that actually got searched
          unsearched (vec (drop 6 planner-phrases)) ; corpus-grounded, never tried
          !ws (workspace/create-workspace)]
      ;; Faithfully reconstruct the post-insufficiency workspace via the real API:
      ;;  - plan_queries stashed the planner's full corpus-grounded output
      (swap! !ws assoc :last-planner-phrases planner-phrases)
      ;;  - one search pass consumed the take-6 batch
      (workspace/record-search! !ws {:queries searched :chunks []})
      ;;  - the sufficiency gate judged the answer insufficient (missing date)
      (workspace/record-sufficiency-decision!
       !ws {:insufficiency {:failure-type :missing-date :target-entity "Altinn 3"}})
      (let [suggestions (workspace/next-research-suggestions
                         @!ws "Når ble Altinn 3 lansert?" "Fant ikke datoen.")
            normset (set (map workspace/normalize-query-text suggestions))]
        (is (seq suggestions) "produces refinement suggestions")
        (is (some normset (map workspace/normalize-query-text unsearched))
            "includes a corpus-grounded phrase the search batch never tried")
        (is (empty? (filter normset (map workspace/normalize-query-text searched)))
            "never re-suggests an already-searched query (normalize-aware removal)")))))

(deftest test-refinement-suggestions-fallback-without-planner-output
  (testing "Without :last-planner-phrases (no plan_queries this turn), refinement still returns cleanly"
    (let [!ws (workspace/create-workspace)]
      (workspace/record-search! !ws {:queries ["altinn" "altinn 3"] :chunks []})
      (workspace/record-sufficiency-decision!
       !ws {:insufficiency {:failure-type :missing-date :target-entity "Altinn 3"}})
      (let [suggestions (workspace/next-research-suggestions
                         @!ws "Når ble Altinn 3 lansert?" "")]
        (is (vector? suggestions) "returns a vector even with no planner output")))))

(deftest test-aggregate-read-signals-escalates-repeated-critical-gaps-to-research
  (testing "Repeated zero-support reads with the same critical gaps trigger re-search even when unread hits remain"
    (let [decision (workspace/aggregate-read-signals
                    {:evidence-plan {:required-claims [{:claim-id :target-entity :critical? true}
                                                       {:claim-id :topic-match :critical? true}
                                                       {:claim-id :answer-bearing-evidence :critical? true}]}
                     :claim-coverage {}
                     :search-history [{:chunk-summaries [{:chunk-id "c1" :doc-num "d1" :chunk-index 0}
                                                         {:chunk-id "c2" :doc-num "d1" :chunk-index 1}
                                                         {:chunk-id "c3" :doc-num "d1" :chunk-index 2}]}]
                     :read-history [{:returned-chunk-ids ["c1"]}
                                    {:returned-chunk-ids ["c2"]}]
                     :read-evaluations [{:status :gap-remaining
                                         :scope-assessment :aligned
                                         :supported-claims []
                                         :remaining-gaps [{:claim-id :target-entity :critical? true :reason :not-addressed}
                                                          {:claim-id :topic-match :critical? true :reason :not-addressed}
                                                          {:claim-id :answer-bearing-evidence :critical? true :reason :not-addressed}]
                                         :contradictions []}
                                        {:status :gap-remaining
                                         :scope-assessment :partially-aligned
                                         :supported-claims []
                                         :remaining-gaps [{:claim-id :target-entity :critical? true :reason :not-addressed}
                                                          {:claim-id :topic-match :critical? true :reason :not-addressed}
                                                          {:claim-id :answer-bearing-evidence :critical? true :reason :not-addressed}]
                                         :contradictions []}]
                     :last-read-signal {:status :gap-remaining
                                        :scope-assessment :partially-aligned
                                        :supported-claims []
                                        :remaining-gaps [{:claim-id :target-entity :critical? true :reason :not-addressed}
                                                         {:claim-id :topic-match :critical? true :reason :not-addressed}
                                                         {:claim-id :answer-bearing-evidence :critical? true :reason :not-addressed}]
                                        :contradictions []}})]
      (is (= :insufficient (:status decision)))
      (is (= :re-search (:suggested-strategy decision)))
      (is (= :repeated-critical-gaps (:reason-code decision)))
      (is (= #{:target-entity :topic-match :answer-bearing-evidence}
             (set (:stagnant-gap-claims decision)))))))

(deftest test-record-turn
  (testing "Records turn history with reasoning and tool calls"
    (let [!ws (workspace/create-workspace)]
      (workspace/record-turn! !ws 0 "I need to search for documents"
                          [{:tool "search_documents" :args {:queries ["test"]} :result-summary "Retrieved 5 chunks"}])
      (workspace/record-turn! !ws 1 "Now I should rerank"
                          [{:tool "rerank_results" :args {:query "test"} :result-summary "Reranked to top 3"}])
      (is (= 2 (count (:iteration-history @!ws))))
      (is (= "I need to search for documents" (-> @!ws :iteration-history first :reasoning)))
      (is (= "search_documents" (-> @!ws :iteration-history first :tool-calls first :tool)))
      (is (= 1 (-> @!ws :iteration-history second :iteration))))))

(deftest test-record-search-and-read-accounting
  (testing "Tracks search history and cumulative read content length"
    (let [!ws (workspace/create-workspace)]
      (workspace/record-search!
       !ws
       {:queries ["one" "two"]
        :filter-by {:fields [{:field "year" :selected-options #{"2024"}}]}
        :chunks [{:chunk_id "c1"} {:chunk_id "c2"}]
        :new-count 2
        :attribution {:merged 2}
        :fallback? false})
      (workspace/record-read!
       !ws
       {:chunk-ids ["c1" "c2"]}
       [{:chunk_id "c1" :content_length 10}
        {:chunk_id "c2" :content_markdown "12345"}])
      (is (= 1 (count (:search-history @!ws))))
      (is (= 1 (count (:read-history @!ws))))
      (is (= 15 (:read-content-length @!ws)))
      (is (= #{"c1" "c2"} (:seen-search-chunk-ids @!ws))))))

(deftest test-record-read-evaluation-preserves-workspace
  (testing "Read evaluation updates workspace instead of replacing it with nil"
    (let [!ws (workspace/create-workspace)
          _ (swap! !ws assoc :evidence-plan {:required-claims [{:claim-id :topic-match
                                                                :critical? true
                                                                :text "topic"}]})
          read-signal {:status :support-found
                       :supported-claims [{:claim-id :topic-match
                                           :support-level :explicit
                                           :chunk-ids ["c1"]}]
                       :remaining-gaps []
                       :contradictions []}]
      (workspace/record-read-evaluation! !ws read-signal)
      (is (map? @!ws))
      (is (= read-signal (:last-read-signal @!ws)))
      (is (= [read-signal] (:read-evaluations @!ws)))
      (is (= {:topic-match {:claim-id :topic-match
                            :support-level :explicit
                            :chunk-ids ["c1"]}}
             (:claim-coverage @!ws)))
      (is (= [] (:open-evidence-gaps @!ws)))
      (is (= [] (:evidence-contradictions @!ws))))))

(deftest test-record-read-evaluation-keeps-support-level-monotonic
  (testing "A later :partial support does not downgrade an earlier :explicit support"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc :evidence-plan {:required-claims [{:claim-id :topic-match
                                                          :critical? true
                                                          :text "topic"}]})
      (workspace/record-read-evaluation!
       !ws
       {:status :support-found
        :supported-claims [{:claim-id :topic-match
                            :support-level :explicit
                            :chunk-ids ["c1"]}]
        :remaining-gaps []
        :contradictions []})
      (workspace/record-read-evaluation!
       !ws
       {:status :gap-remaining
        :supported-claims [{:claim-id :topic-match
                            :support-level :partial
                            :chunk-ids ["c2"]}]
        :remaining-gaps []
        :contradictions []})
      (is (= :explicit (get-in @!ws [:claim-coverage :topic-match :support-level]))
          "explicit support must not be downgraded by a later partial read")
      (is (= ["c1" "c2"] (get-in @!ws [:claim-coverage :topic-match :chunk-ids]))
          "chunk ids still accumulate across reads"))))

(deftest test-record-read-evaluation-tracks-non-supporting-chunks
  (testing "Zero-support non-degraded reads mark their chunk ids as non-supporting for the active query"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc :evidence-plan {:required-claims [{:claim-id :topic-match
                                                          :critical? true
                                                          :text "topic"}]})
      (workspace/record-read-evaluation!
       !ws
       {:status :gap-remaining
        :scope-assessment :aligned
        :chunk-ids ["c1" "c2"]
        :supported-claims []
        :remaining-gaps [{:claim-id :topic-match
                          :critical? true
                          :reason :not-addressed}]
        :contradictions []
        :degraded? false})
      (is (= #{"c1" "c2"} (:non-supporting-chunk-ids @!ws)))))
  (testing "Ambiguous-scope reads do NOT mark chunks non-supporting — rereads stay possible after query reframing"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc :evidence-plan {:required-claims [{:claim-id :topic-match
                                                          :critical? true
                                                          :text "topic"}]})
      (workspace/record-read-evaluation!
       !ws
       {:status :gap-remaining
        :scope-assessment :ambiguous
        :chunk-ids ["c1" "c2"]
        :supported-claims []
        :remaining-gaps [{:claim-id :topic-match
                          :critical? true
                          :reason :not-addressed}]
        :contradictions []
        :degraded? false})
      (is (= #{} (:non-supporting-chunk-ids @!ws))))))

(deftest test-execute-tool-call-search-surfaces-backend-errors
  (testing "Search backend failures are surfaced explicitly instead of recorded as zero-hit searches"
    (let [!ws (workspace/create-workspace)
          ambient-ctx {:docs-collection "docs"
                       :chunks-collection "chunks"
                       :phrases-collection "phrases"
                       :conversation-history []
                       :opts {:tenant "ka" :environment "dev"}}]
      (with-redefs [tools/execute-sub-skill (fn [_skill-id _inputs _opts _parameters]
                                              {:error {:error-type :retrieval-backend-failure
                                                       :error-message "Retrieval backend failure: Connection refused"}})]
        (let [result (tools/execute-tool-call
                      "search"
                      {:queries ["årsverk Digdir 2022"]}
                      !ws
                      ambient-ctx)]
          (is (re-find #"Search backend error" result))
          (is (re-find #"Connection refused" result))
          (is (empty? (:search-history @!ws)))
          (is (= 1 (count (:search-errors @!ws))))
          (is (= :retrieval-backend-failure
                 (get-in @!ws [:search-errors 0 :error-type]))))))))

(deftest test-execute-tool-call-search-with-iteration-returns-timing
  (testing "Five-argument tool execution returns a timing envelope"
    (let [!ws (workspace/create-workspace)
          ambient-ctx {:docs-collection "docs"
                       :chunks-collection "chunks"
                       :phrases-collection "phrases"
                       :conversation-history []
                       :opts {:tenant "ka" :environment "dev"
                              :skill-params {}}}]
      (with-redefs [tools/execute-sub-skill
                    (fn [_skill-id _inputs _opts _parameters]
                      (skills/success-result
                       {:chunks []
                        :search-attribution {:merged 0}}
                       {}))]
        (let [result (tools/execute-tool-call
                      "search"
                      {:queries ["test"]}
                      !ws
                      ambient-ctx
                      3)]
          (is (map? result))
          (is (= :search (:stage result)))
          (is (= "search" (:tool result)))
          (is (string? (:result-text result)))
          (is (number? (:duration-ms result)))
          (is (not (neg? (:duration-ms result)))))))))

;; =============================================================================
;; Tool Result Formatting Tests
;; =============================================================================

(deftest test-format-search-result-success
  (testing "Formats successful search result"
    (let [result {:outputs {:chunks [{:chunk_id "1"} {:chunk_id "2"}]}}
          formatted (tools/format-search-result result 2 5)]
      (is (string? formatted))
      (is (re-find #"Retrieved 2 chunks" formatted))
      (is (re-find #"2 new unique" formatted))
      (is (re-find #"5 total chunks" formatted)))))

(deftest test-format-search-metadata-results-surfaces-matched-questions
  (testing "Read-tool integration: matched_q renders on chunks with :matched-questions"
    (let [chunks [{:chunk_id "enriched"
                   :doc_num "d1"
                   :chunk_index 4
                   :content_length 478
                   :metadata "About > launch"
                   :original-rank 0.87
                   :search-types #{:hypothetical-questions :content}
                   :matched-questions ["Når kom første versjon av Altinn 3?"]}
                  {:chunk_id "plain"
                   :doc_num "d2"
                   :chunk_index 0
                   :content_length 1234
                   :search-types #{:content}}]
          formatted (tools/format-search-metadata-results chunks 2 2 1 {} 10)]
      (is (re-find #"matched_q=" formatted)
          "Chunk with :matched-questions surfaces matched_q= in the preview")
      (is (re-find #"\"Når kom første versjon av Altinn 3\?\"" formatted)
          "Matched question text appears verbatim, quoted")
      (is (not (re-find #"plain.*matched_q=" formatted))
          "Chunks without :matched-questions don't carry the field"))))

(deftest test-format-search-metadata-results-no-matched-q-when-empty
  (testing "Empty :matched-questions vector is treated as absent (no leaked field)"
    (let [chunks [{:chunk_id "c1"
                   :doc_num "d1"
                   :chunk_index 0
                   :matched-questions []}]
          formatted (tools/format-search-metadata-results chunks 1 1 1 {} 10)]
      (is (not (re-find #"matched_q=" formatted))
          "Empty vector must not render an empty matched_q= field"))))

(deftest test-format-search-metadata-results-caps-matched-questions-at-two
  (testing "Preview caps displayed matched questions at 2 (gist over flood)"
    (let [chunks [{:chunk_id "c1"
                   :doc_num "d1"
                   :chunk_index 0
                   :matched-questions ["q1" "q2" "q3" "q4"]}]
          formatted (tools/format-search-metadata-results chunks 1 1 1 {} 10)]
      (is (re-find #"\"q1\"" formatted))
      (is (re-find #"\"q2\"" formatted))
      (is (not (re-find #"\"q3\"" formatted))
          "Third question must NOT render (cap)")
      (is (not (re-find #"\"q4\"" formatted))
          "Fourth question must NOT render"))))

(deftest test-format-search-metadata-results-renders-snippet-when-enabled
  (testing "snippet= renders only when show-snippet? is true and :snippet present"
    (let [chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                   :snippet "Maks filstørrelse for opplasting er 250 MB per fil."}]
          on  (tools/format-search-metadata-results chunks 1 1 1 {} 10 true)
          off (tools/format-search-metadata-results chunks 1 1 1 {} 10 false)]
      (is (re-find #"snippet=" on) "snippet= renders when enabled")
      (is (re-find #"250 MB" on) "snippet text appears")
      (is (not (re-find #"snippet=" off))
          "snippet= absent when disabled (control = metadata-only)")
      (is (not (re-find #"snippet=" (tools/format-search-metadata-results chunks 1 1 1 {} 10)))
          "6-arg arity defaults to snippets off"))))

(deftest test-format-search-metadata-results-snippet-blank-or-absent
  (testing "Blank or missing :snippet never renders an empty snippet= field"
    (let [chunks [{:chunk_id "blank" :doc_num "d1" :chunk_index 0 :snippet "   "}
                  {:chunk_id "missing" :doc_num "d2" :chunk_index 1}]
          formatted (tools/format-search-metadata-results chunks 2 2 1 {} 10 true)]
      (is (not (re-find #"snippet=" formatted))
          "Neither a blank string nor an absent key may render snippet="))))

(deftest test-format-search-result-error
  (testing "Formats search error result"
    (let [result {:error {:error-message "Connection failed"}}
          formatted (tools/format-search-result result 0 0)]
      (is (re-find #"Error executing search_documents" formatted))
      (is (re-find #"Connection failed" formatted)))))

(deftest test-format-plan-result-success
  (testing "Formats successful plan result"
    (let [result {:outputs {:queries ["phrase 1" "phrase 2" "phrase 3"]}}
          formatted (tools/format-plan-result result)]
      (is (re-find #"Generated 3 queries" formatted))
      (is (re-find #"phrase 1" formatted)))))

(deftest test-format-plan-result-error
  (testing "Formats plan error result"
    (let [result {:error {:error-message "Model unavailable"}}
          formatted (tools/format-plan-result result)]
      (is (re-find #"Error executing plan_queries" formatted)))))

(deftest test-format-rerank-result-success
  (testing "Formats successful rerank result"
    (let [result {:outputs {:chunks [{:chunk_id "1" :content_markdown "Relevant content"}
                                     {:chunk_id "2" :content_markdown "Also relevant"}]}}
          formatted (tools/format-rerank-result result)]
      (is (re-find #"Reranked to top 2 chunks" formatted)))))

(deftest test-format-rerank-result-error
  (testing "Formats rerank error result"
    (let [result {:error {:error-message "ColBERT unavailable"}}
          formatted (tools/format-rerank-result result)]
      (is (re-find #"Error executing rerank_results" formatted)))))

(deftest test-format-generate-result-success
  (testing "Formats successful generation result"
    (let [result {:outputs {:response "The answer is 42."}}
          formatted (tools/format-generate-result result {:status :enough})]
      (is (= "The answer is 42." formatted)))))

(deftest test-format-generate-result-insufficient-context-flag
  (testing "Uses sufficiency guidance when synthesis returns structured insufficient-context signal"
    (let [result {:outputs {:response "I cannot conclude from the available material."
                            :insufficient-context true}}
          formatted (tools/format-generate-result result {:status :insufficient
                                                          :message "read more first"})]
      (is (= "read more first" formatted)))))

(deftest test-format-generate-result-insufficient-context-fallback
  (testing "Uses supplied fallback guidance when structured signal is absent"
    (let [result {:outputs {:response "There is not enough information in the provided sources to answer this."}}
          formatted (tools/format-generate-result result {:status :insufficient
                                                          :message "new search"})]
      (is (= "new search" formatted)))))

(deftest test-format-generate-result-error
  (testing "Formats generation error result"
    (let [result {:error {:error-message "Token limit exceeded"}}
          formatted (tools/format-generate-result result nil)]
      (is (re-find #"Error executing generate_response" formatted)))))

(deftest test-format-generate-result-uses-decision-message
  (testing "Returns sufficiency decision message when provided"
    (let [result {:outputs {:response "base"}}
          formatted (tools/format-generate-result result {:status :insufficient
                                                          :message "guided"})]
      (is (= "guided" formatted)))))

(deftest test-format-trace-file-includes-built-synthesis-prompt
  (testing "Trace output records the final built synthesis prompt for debugging"
    (let [trace-text (#'core/format-trace-file
                      {:query "Hvor mange årsverk hadde Digdir i 2022?"
                       :model "gpt-4o"
                       :trace []
                       :queries []
                       :chunks-count 0
                       :status :success
                       :response "326 utforte arsverk [1]"
                       :skill-id ":builtin/agent"
                       :skill-graph-id "builtin/agent-rag"
                       :synthesis-prompts {:system "system prompt"
                                           :full "base prompt text\n\nambiguity instruction appended"}})]
      (is (re-find #"== SYNTHESIS PROMPTS ==" trace-text))
      (is (re-find #"\[system\] system prompt" trace-text))
      (is (re-find #"\[full\] base prompt text" trace-text))
      (is (re-find #"ambiguity instruction appended" trace-text)))))

(deftest test-format-trace-file-includes-timings
  (testing "Trace output records per-stage timings and tool durations"
    (let [trace-text (#'core/format-trace-file
                      {:query "q"
                       :model "gpt-4o"
                       :trace [{:iteration 0
                                :reasoning "thinking"
                                :tool-calls [{:tool "search"
                                              :args {:queries ["q"]}
                                              :result-summary "Retrieved 1 chunk"
                                              :duration-ms 11
                                              :stage :search}]}]
                       :queries ["q"]
                       :chunks-count 1
                       :status :success
                       :response "ok"
                       :skill-id ":builtin/agent"
                       :skill-graph-id "builtin/agent-rag"
                        :duration-ms 123
                        :stage-timings [{:stage :agent-llm
                                         :iteration 0
                                         :duration-ms 45
                                         :status :ok}
                                        {:stage :summarize
                                         :iteration 1
                                         :tool "summarize"
                                         :sub-skill :builtin/summarization
                                         :duration-ms 27
                                         :status :ok}
                                        {:stage :citation-backfill-synthesis
                                         :duration-ms 9
                                         :tool "backfill_citations"
                                         :sub-skill :builtin/synthesis
                                         :status :ok
                                        :input-length 20
                                        :output-length 8}]})]
      (is (re-find #"\[duration-ms\] 123" trace-text))
      (is (re-find #"== STAGE TIMINGS ==" trace-text))
      (is (re-find #"stage=agent-llm iteration=0 duration-ms=45 status=ok" trace-text))
      (is (re-find #"stage=summarize iteration=1 tool=summarize sub-skill=summarization duration-ms=27 status=ok" trace-text))
      (is (re-find #"stage=citation-backfill-synthesis .*tool=backfill_citations .*sub-skill=synthesis .*duration-ms=9" trace-text))
      ;; Per-iteration tool calls are now nested inside the synthesized step's
      ;; [outputs] block (Phase 2.0 — agent trace adopts graph-trace envelope).
      ;; The data is the same; the shape is Clojure-literal inside :tool-calls.
      (is (re-find #":tool \"search\"" trace-text))
      (is (re-find #":stage :search" trace-text))
      (is (re-find #":duration-ms 11" trace-text)))))

(deftest test-infer-query-intent-numeric-fact
  (testing "Infers numeric fact intent with entity, year, metric, and canonical doc family"
    (let [intent (workspace/infer-query-intent
                  "Hvor mange årsverk hadde Digdir i 2022?"
                  [])]
      (is (= :numeric-fact (:answer-type intent)))
      (is (= "Digdir" (:entity intent)))
      (is (= "2022" (:year-or-date intent)))
      (is (= "årsverk" (:metric intent)))
      (is (= "årsrapport" (:doc-family-preference intent)))
      (is (some #{:year-bounded} (:scope-signals intent)))
      (is (some #{:organization-bounded} (:scope-signals intent))))))

(deftest test-infer-query-intent-definition
  (testing "Infers definition-style intent from explanatory queries"
    (let [intent (workspace/infer-query-intent
                  "Hva er Digdir?"
                  [])]
      (is (= :definition (:answer-type intent)))
      (is (= "Digdir" (:entity intent)))
      (is (nil? (:year-or-date intent)))
      (is (nil? (:metric intent))))))

(deftest test-infer-query-intent-comparison-uses-history
  (testing "Infers comparison intent and doc family preference from conversation history"
    (let [intent (workspace/infer-query-intent
                  "Sammenlign dem"
                  [{:message/role :user
                    :message/text "Sammenlign årsverk i Digdir i 2021 og 2022 fra årsrapporten."}])]
      (is (= :comparison (:answer-type intent)))
      (is (= "Digdir" (:entity intent)))
      (is (= "2021" (:year-or-date intent)))
      (is (= "årsverk" (:metric intent)))
      (is (= "årsrapport" (:doc-family-preference intent)))
      (is (some #{:year-bounded} (:scope-signals intent))))))

(deftest test-infer-query-intent-ignores-followup-instructions
  (testing "Infers entity from the primary question clause, not trailing steering instructions"
    (let [intent (workspace/infer-query-intent
                  "Når ble Altinn 3 lansert? Hvis beste treff ikke inneholder en eksplisitt lanseringsdato, les nabochunkene i samme dokument rundt det mest relevante treffet før du svarer. Ikke gjør et nytt søk før du har utvidet konteksten i samme dokument."
                  [])]
      (is (= :lookup (:answer-type intent)))
      (is (= "Altinn" (:entity intent)))
      (is (nil? (:year-or-date intent)))
      (is (nil? (:metric intent))))))

(deftest test-planned-query-batch-adds-intent-aware-variants
  (testing "Combines planner output with deterministic broad, metric-specific, and doc-aware variants"
    (let [queries (workspace/planned-query-batch
                   "Hvor mange årsverk hadde Digdir i 2022?"
                   []
                   ["digdir 2022 bemanning"]
                   [])]
      (is (some #{"årsverk digdir 2022"} queries))
      (is (some #{"utførte årsverk digdir 2022"} queries))
      (is (some #{"digdir årsrapport 2022 årsverk"} queries))
      (is (some #{"digdir antall ansatte årsverk 2022"} queries))
      (is (some #{"digdir 2022 bemanning"} queries)))))

(deftest test-format-trace-file-includes-structured-insufficiency
  (testing "Trace output records structured insufficiency payloads for debugging"
    (let [trace-text (#'core/format-trace-file
                      {:query "Hvor mange årsverk hadde Digdir i 2022?"
                       :model "gpt-4o"
                       :trace []
                       :queries []
                       :chunks-count 0
                       :status :success
                       :response "Fant ikke svaret."
                       :skill-id ":builtin/agent"
                       :skill-graph-id "builtin/agent-rag"
                       :sufficiency-decisions [{:status :insufficient
                                                :action :re-search
                                                :insufficiency {:failure-type :missing-numeric-fact
                                                                :target-entity "Digdir"
                                                                :target-year "2022"
                                                                :target-metric "årsverk"}}]})]
      (is (re-find #"== SUFFICIENCY DECISIONS ==" trace-text))
      (is (re-find #"\[insufficiency\]" trace-text))
      (is (re-find #":failure-type :missing-numeric-fact" trace-text))
      (is (re-find #":target-year \"2022\"" trace-text))
      (is (re-find #":target-metric \"årsverk\"" trace-text)))))

(deftest test-format-trace-file-distinguishes-conflict-status
  (testing "Trace output shows conflict decisions distinctly from insufficiency"
    (let [trace-text (#'core/format-trace-file
                      {:query "Hvor mange årsverk hadde Digdir i 2022?"
                       :model "gpt-4o"
                       :trace []
                       :queries []
                       :chunks-count 0
                       :status :success
                       :response "Motstridende tall."
                       :skill-id ":builtin/agent"
                       :skill-graph-id "builtin/agent-rag"
                       :sufficiency-decisions [{:status :conflict
                                                :action :re-search
                                                :insufficiency {:failure-type :conflict
                                                                :target-entity "Digdir"
                                                                :target-year "2022"
                                                                :target-metric "årsverk"}}]})]
      (is (re-find #"status=conflict action=re-search" trace-text))
      (is (re-find #":failure-type :conflict" trace-text)))))

(deftest test-format-trace-file-includes-budget-state
  (testing "Trace output records budget state for debugging"
    (let [trace-text (#'core/format-trace-file
                      {:query "q"
                       :model "gpt-4o"
                       :trace []
                       :queries []
                       :chunks-count 0
                       :status :success
                       :response "ok"
                       :skill-id ":builtin/agent"
                       :skill-graph-id "builtin/agent-rag"
                       :budget-state {:search-passes-used 1
                                      :search-passes-remaining 0
                                      :read-operations-used 2
                                      :read-operations-remaining 1}})]
      (is (re-find #"\[budget-state\]" trace-text))
      (is (re-find #":search-passes-remaining 0" trace-text)))))

(deftest test-format-trace-file-includes-query-intent
  (testing "Trace output records inferred query intent for retrieval debugging"
    (let [trace-text (#'core/format-trace-file
                      {:query "Hvor mange årsverk hadde Digdir i 2022?"
                       :model "gpt-4o"
                       :trace []
                       :queries []
                       :query-intent {:answer-type :numeric-fact
                                      :entity "Digdir"
                                      :year-or-date "2022"
                                      :metric "årsverk"
                                      :doc-family-preference "årsrapport"
                                      :scope-signals [:year-bounded :organization-bounded]}
                       :chunks-count 0
                       :status :success
                       :response "326 utførte årsverk [1]"
                       :skill-id ":builtin/agent"
                       :skill-graph-id "builtin/agent-rag"})]
      (is (re-find #"\[query-intent\]" trace-text))
      (is (re-find #":answer-type :numeric-fact" trace-text))
      (is (re-find #":doc-family-preference \"årsrapport\"" trace-text)))))

(deftest test-format-trace-file-includes-read-signal-sections
  (testing "Trace output records read signals and shadow sufficiency decisions deterministically"
    (let [trace-text (#'core/format-trace-file
                      {:query "Når ble Altinn 3 lansert?"
                       :model "gpt-4o"
                       :trace []
                       :queries []
                       :chunks-count 0
                       :status :success
                       :response "Fant ikke datoen."
                       :skill-id ":builtin/agent"
                       :skill-graph-id "builtin/agent-rag"
                       :evidence-plan {:required-claims [{:claim-id :target-entity}
                                                         {:claim-id :topic-match}]}
                       :claim-coverage {:target-entity {:claim-id :target-entity}}
                       :read-evaluations [{:status :gap-remaining
                                           :scope-assessment :aligned
                                           :next-action-hint :read-more
                                           :confidence 0.71
                                           :degraded? false
                                           :evaluation-mode :llm
                                           :supported-claims [{:claim-id :target-entity
                                                               :support-level :explicit
                                                               :chunk-ids ["c1"]}]
                                           :remaining-gaps [{:claim-id :topic-match
                                                             :critical? true
                                                             :reason :not-addressed
                                                             :text "Need launch detail"}]
                                           :contradictions []}]
                       :last-read-signal {:status :gap-remaining
                                          :scope-assessment :aligned
                                          :next-action-hint :read-more
                                          :confidence 0.71
                                          :degraded? false
                                          :evaluation-mode :llm
                                          :supported-claims [{:claim-id :target-entity
                                                              :support-level :explicit
                                                              :chunk-ids ["c1"]}]
                                          :remaining-gaps [{:claim-id :topic-match
                                                            :critical? true
                                                            :reason :not-addressed
                                                            :text "Need launch detail"}]
                                          :contradictions []}
                       :open-evidence-gaps [{:claim-id :topic-match}]
                       :shadow-sufficiency-decisions [{:status :insufficient
                                                      :suggested-strategy :read-more
                                                      :reason-code :critical-gaps-remaining
                                                      :missing-claims [:topic-match]}]})]
      (is (re-find #"== READ SIGNALS ==" trace-text))
      (is (re-find #"\[last\]" trace-text))
      (is (re-find #"status=gap-remaining scope=aligned hint=read-more" trace-text))
      (is (re-find #"\[supported 1\] claim=target-entity support=explicit" trace-text))
      (is (re-find #"\[gap 1\] claim=topic-match  critical=true reason=not-addressed" trace-text))
      (is (re-find #"== SHADOW SUFFICIENCY DECISIONS ==" trace-text))
      (is (re-find #"status=insufficient action=read-more reason-code=critical-gaps-remaining missing-claims=topic-match" trace-text)))))

(deftest test-format-trace-file-separates-response-validations
  (testing "Trace output renders response validation entries outside the sufficiency-decision section"
    (let [trace-text (#'core/format-trace-file
                      {:query "Hvordan fungerer autorisasjon i Altinn 3?"
                       :model "gpt-4o"
                       :trace []
                       :queries []
                       :chunks-count 0
                       :status :success
                       :response "Uferdig svar."
                       :skill-id ":builtin/agent"
                       :skill-graph-id "builtin/agent-rag"
                       :response-validations [{:status :insufficient
                                               :action :read-more
                                               :source :response-validation
                                               :iteration 2
                                               :message "Need one more source section."
                                               :insufficiency {:failure-type :missing-procedure-step}}]})]
      (is (re-find #"== RESPONSE VALIDATIONS ==" trace-text))
      (is (re-find #"status=insufficient action=read-more source=response-validation iteration=2" trace-text))
      (is (re-find #":failure-type :missing-procedure-step" trace-text)))))

;; =============================================================================
;; Citation Carry-Through Tests
;; =============================================================================

(deftest test-citation-carry-through-no-sources
  (testing "Leaves final response unchanged when no citations were produced"
    (let [!ws (atom {:citations [] :last-generated-response nil})
          result (workspace/ensure-citation-carry-through "Final answer." !ws)]
      (is (= "Final answer." (:response result)))
      (is (false? (:carried-through? result)))
      (is (= :already-cited-or-no-sources (:strategy result))))))

(deftest test-citation-carry-through-use-generated
  (testing "Uses last generated response when final answer dropped citations"
    (let [!ws (atom {:citations [{:index 1 :chunk-id "c1"}]
                     :last-generated-response "The answer is 42 [1]."})
          result (workspace/ensure-citation-carry-through "The answer is 42." !ws)]
      (is (= "The answer is 42 [1]." (:response result)))
      (is (:carried-through? result))
      (is (= :use-last-generated-with-citations (:strategy result))))))

(deftest test-citation-carry-through-no-inline-fallback
  (testing "Does not append synthetic source markers when inline citations are unavailable"
    (let [!ws (atom {:citations [{:index 2 :chunk-id "c2"}
                                 {:index 1 :chunk-id "c1"}]
                     :last-generated-response "No citations here."})
          result (workspace/ensure-citation-carry-through "Final answer." !ws)]
      (is (= "Final answer." (:response result)))
      (is (false? (:carried-through? result)))
      (is (= :no-inline-citations-available (:strategy result))))))

(deftest test-citation-carry-through-falls-back-to-citation-index
  (testing "Does not append citation-index source markers when parsed citations are absent"
    (let [!ws (atom {:citations []
                     :citation-index {3 "c3" 1 "c1" 2 "c2"}
                     :last-generated-response "No citations here."})
          result (workspace/ensure-citation-carry-through "Final answer." !ws)]
      (is (= "Final answer." (:response result)))
      (is (false? (:carried-through? result)))
      (is (= :no-inline-citations-available (:strategy result))))))

(deftest test-citation-backfill-via-synthesis
  (testing "Backfills citations by running synthesis when final answer has no citation data"
    (let [!ws (atom {:chunks {"c1" {:chunk_id "c1" :content_markdown "Chunk 1"}
                              "c2" {:chunk_id "c2" :content_markdown "Chunk 2"}}
                     :citations []
                     :citation-index {}})
          ambient-ctx {:opts {:tenant "t" :environment "e" :skill-params {}}}
          result (with-redefs [tools/execute-sub-skill
                               (fn [skill-id inputs _opts]
                                 (is (= :builtin/synthesis skill-id))
                                 (is (= "Q?" (:query inputs)))
                                 {:outputs {:response "Svar med kilde [1]."
                                            :citations [{:index 1 :chunk-id "c1"}]
                                            :citation-index {1 "c1"}}})]
                   (#'core/maybe-backfill-citations-via-synthesis!
                    "Svar uten kilde."
                    "Q?"
                    !ws
                    ambient-ctx))]
      (is (= "Svar med kilde [1]." (:response result)))
      (is (true? (:backfilled? result)))
      (is (= :synthesis-backfill (:strategy result)))
      (is (= [{:index 1 :chunk-id "c1"}] (:citations @!ws)))
      (is (= {1 "c1"} (:citation-index @!ws))))))

(deftest test-citation-backfill-skips-when-inline-citations-exist
  (testing "Skips synthesis backfill when final answer already contains inline citations"
    (let [!ws (atom {:chunks {"c1" {:chunk_id "c1" :content_markdown "Chunk 1"}}})
          ambient-ctx {:opts {:tenant "t" :environment "e" :skill-params {}}}
          calls (atom 0)
          result (with-redefs [tools/execute-sub-skill
                               (fn [& _]
                                 (swap! calls inc)
                                 {:outputs {:response "should not happen"}})]
                   (#'core/maybe-backfill-citations-via-synthesis!
                    "Allerede sitert [1]."
                    "Q?"
                    !ws
                    ambient-ctx))]
      (is (= "Allerede sitert [1]." (:response result)))
      (is (false? (:backfilled? result)))
      (is (= :already-inline-citations (:strategy result)))
      (is (= 0 @calls)))))

;; =============================================================================
;; Tool Definition Tests
;; =============================================================================

(deftest test-agent-tool-definition-structure
  (testing "Agent tool definition has valid OpenAI function calling format"
    (let [tool core/agent-tool-definition]
      (is (= "function" (:type tool)))
      (is (= "run_agent" (get-in tool [:function :name])))
      (is (string? (get-in tool [:function :description])))
      (is (= "object" (get-in tool [:function :parameters :type])))
      (is (contains? (get-in tool [:function :parameters :properties]) :query))
      (is (= ["query"] (get-in tool [:function :parameters :required]))))))

(deftest test-agent-loop-tools-structure
  (testing "Internal agent tools have valid structure"
    (doseq [tool tools/agent-tools]
      (is (= "function" (:type tool)))
      (is (string? (get-in tool [:function :name])))
      (is (string? (get-in tool [:function :description])))
      (is (map? (get-in tool [:function :parameters])))
      (is (= "object" (get-in tool [:function :parameters :type]))))
    (is (= 6 (count tools/agent-tools)))
    (let [tool-names (set (map #(get-in % [:function :name]) tools/agent-tools))]
      (is (contains? tool-names "search"))
      (is (contains? tool-names "read_chunks"))
      (is (contains? tool-names "plan_queries"))
      (is (contains? tool-names "inspect_filters"))
      (is (contains? tool-names "rerank_results"))
      (is (contains? tool-names "generate_response")))))

(deftest test-agent-tool-definitions-add-dataset-bound-aliases
  (testing "Agent tool surface exposes dataset-bound aliases without duplicating generic shared tools"
    (let [ambient-ctx {:dataset-ref {:tenant "altinn-docs"
                                     :dataset-config-key "dev"}
                       :allowed-dataset-scopes [{:tenant "altinn-docs"
                                                 :dataset-config-key "dev"}
                                                {:tenant "ka"
                                                 :dataset-config-key "kudos"}]}
          tool-names (mapv #(get-in % [:function :name])
                           (tools/agent-tool-definitions ambient-ctx))]
      (is (contains? (set tool-names) "query_altinn_docs"))
      (is (contains? (set tool-names) "read_altinn_docs"))
      (is (contains? (set tool-names) "inspect_altinn_docs_filters"))
      (is (contains? (set tool-names) "query_kudos"))
      (is (contains? (set tool-names) "read_kudos"))
      (is (contains? (set tool-names) "inspect_kudos_filters"))
      (is (not (contains? (set tool-names) "search")))
      (is (not (contains? (set tool-names) "read_chunks")))
      (is (not (contains? (set tool-names) "inspect_filters")))
      (is (contains? (set tool-names) "plan_queries"))
      (is (contains? (set tool-names) "rerank_results"))
      (is (contains? (set tool-names) "generate_response")))))

(deftest test-dataset-bound-query-tool-uses-bound-dataset-context
  (testing "Dataset-bound query alias resolves to retrieval with the alias dataset scope"
    (let [!ws (workspace/create-workspace)
          captured (atom nil)
          ambient-ctx {:dataset-ref {:tenant "ka"
                                     :dataset-config-key "kudos"}
                       :allowed-dataset-scopes [{:tenant "altinn-docs"
                                                 :dataset-config-key "dev"}
                                                {:tenant "ka"
                                                 :dataset-config-key "kudos"}]
                       :docs-collection "ka-docs"
                       :chunks-collection "ka-chunks"
                       :phrases-collection "ka-phrases"
                       :conversation-history []
                       :opts {:tenant "ka"
                              :dataset-config-key "dev"
                              :dataset-ref {:tenant "ka"
                                            :dataset-config-key "kudos"}
                              :skill-params {}}}]
      (with-redefs [ctx/resolve-dataset-context
                    (fn [dataset-ref]
                      {:tenant (:tenant dataset-ref)
                       :dataset-config-key (:dataset-config-key dataset-ref)
                       :dataset-inputs {:docs-collection (str (:tenant dataset-ref) "-docs")
                                        :chunks-collection (str (:tenant dataset-ref) "-chunks")
                                        :phrases-collection (str (:tenant dataset-ref) "-phrases")}})
                    tools/execute-sub-skill
                    (fn [_skill-id inputs opts & [parameters]]
                      (reset! captured {:inputs inputs
                                        :opts opts
                                        :parameters parameters})
                      {:outputs {:chunks []}})]
        (tools/execute-tool-call
         "query_altinn_docs"
         {:queries ["årsverk digdir 2022"]}
         !ws
         ambient-ctx)
        (is (= {:tenant "altinn-docs"
                :dataset-config-key "dev"}
               (get-in @captured [:opts :dataset-ref])))
        (is (= "altinn-docs"
               (get-in @captured [:opts :tenant])))
        (is (= "altinn-docs-docs"
               (get-in @captured [:inputs :docs-collection])))
        (is (= ["årsverk digdir 2022"]
               (get-in @captured [:inputs :queries])))
        (is (true? (get-in @captured [:parameters :metadata-only])))))))

;; =============================================================================
;; Registration Tests
;; =============================================================================

(deftest test-agent-registered
  (testing "Agent skill is registered after initialization"
    (let [skill-ids (set (map :skill-id (api/list-skills)))]
      (is (contains? skill-ids :builtin/agent)))))

(deftest test-agent-skill-info
  (testing "Agent skill info is retrievable via API"
    (let [info (api/get-skill-info :builtin/agent)]
      (is (some? info))
      (is (= :builtin/agent (:skill-id info)))
      (is (= :orchestration (:category info))))))

(deftest test-agent-tool-definition-in-api
  (testing "Agent tool definition is available via API"
    (let [tool (api/get-skill-tool-definition :builtin/agent)]
      (is (some? tool))
      (is (= "function" (:type tool)))
      (is (= "run_agent" (get-in tool [:function :name]))))))

(deftest test-agent-in-all-tool-definitions
  (testing "Agent appears in get-all-tool-definitions"
    (let [tools (api/get-all-tool-definitions)
          tool-names (set (map #(get-in % [:function :name]) tools))]
      (is (contains? tool-names "run_agent")))))

;; =============================================================================
;; Parse Tool Args Tests
;; =============================================================================

(deftest test-parse-tool-args-success
  (testing "Parses valid JSON arguments"
    (let [result (tools/parse-tool-args "{\"queries\": [\"test query\"]}")]
      (is (= {:queries ["test query"]} result)))))

(deftest test-parse-tool-args-error
  (testing "Returns error map for invalid JSON"
    (let [result (tools/parse-tool-args "not json")]
      (is (contains? result :error))
      (is (re-find #"Failed to parse" (:error result))))))

(deftest test-normalize-filter-by
  (testing "Normalizes filter_by payload with snake_case keys"
    (let [filter-by (tools/normalize-filter-by
                      {:fields [{:field "owner_short"
                                 :selected_options ["Digdir" "DFD"]
                                 :value_type "string"}
                                {:field "year"
                                 :selected_options ["2023"]
                                 :value_type "integer"}]})]
      (is (= #{"Digdir" "DFD"}
             (set (get-in filter-by [:fields 0 :selected-options]))))
      (is (= :string (get-in filter-by [:fields 0 :value-type])))
      (is (= :integer (get-in filter-by [:fields 1 :value-type]))))))

(deftest test-normalize-filter-by-contains
  (testing "Normalizes contains filter payload"
    (let [filter-by (tools/normalize-filter-by
                      {:fields [{:field "title"
                                 :type "contains"
                                 :value "2022"
                                 :value_type "string"}]})]
      (is (= :contains (get-in filter-by [:fields 0 :type])))
      (is (= "2022" (get-in filter-by [:fields 0 :value])))
      (is (= :string (get-in filter-by [:fields 0 :value-type]))))))

(deftest test-normalize-filter-by-empty
  (testing "Returns nil for empty or invalid filter payloads"
    (is (nil? (tools/normalize-filter-by nil)))
    (is (nil? (tools/normalize-filter-by {:fields []})))
    (is (nil? (tools/normalize-filter-by {:fields [{:field "owner_short"}]})))))

(deftest test-search-documents-falls-back-when-filtered-empty
  (testing "Retries unfiltered retrieval when filtered search returns no chunks"
    (let [!ws (workspace/create-workspace)
          calls (atom [])]
      (with-redefs [tools/execute-sub-skill
                    (fn [_skill-id _inputs _opts & [params]]
                      (swap! calls conj params)
                      (if (seq (:filter-by params))
                        {:outputs {:chunks []}}
                        {:outputs {:chunks [{:chunk_id "c1" :content_markdown "hit"}]}}))]
        (let [summary (tools/execute-tool-call
                        "search"
                        {:queries ["digdir årsverk 2022"]
                         :filter_by {:fields [{:field "year"
                                               :selected_options ["2022"]
                                               :value_type "integer"}]}}
                        !ws
                        {:docs-collection "docs"
                         :chunks-collection "chunks"
                         :phrases-collection "phrases"
                         :conversation-history []
                         :opts {:tenant "t" :environment "e"}})]
          (is (re-find #"retried without filters" summary))
          (is (= 2 (count @calls)))
          ;; Search populates seen-search-chunk-ids, NOT workspace chunks
          (is (= 0 (count (:chunks @!ws))))
          (is (= 1 (count (:seen-search-chunk-ids @!ws)))))))))

(deftest test-execute-sub-skill-explicit-params-override-config
  (testing "execute-sub-skill merges per-skill config params and lets explicit params win"
    (let [captured-ctx (atom nil)]
      (with-redefs [ctx/build-execution-context
                    (fn [_skill-id _inputs opts]
                      (let [fake-ctx {:inputs {} :parameters (:parameters opts) :services {} :skill-params {}}]
                        (reset! captured-ctx fake-ctx)
                        fake-ctx))
                    skills/execute-skill
                    (fn [_skill-id _exec-ctx]
                      {:outputs {}})]
        (tools/execute-sub-skill
          :builtin/retrieval
          {:queries ["digdir årsverk 2022"]}
          {:tenant "t"
           :environment "e"
           :skill-params {:builtin/retrieval {:limit 60
                                              :auto-filter false
                                              :filter-by {:fields [{:field "owner_short"
                                                                    :selected-options #{"Digdir"}
                                                                    :value-type :string}]}}}}
          {:filter-by {:fields [{:field "year"
                                 :selected-options #{"2022"}
                                 :value-type :integer}]}})
        (is (= 60 (get-in @captured-ctx [:parameters :limit])))
        (is (false? (get-in @captured-ctx [:parameters :auto-filter])))
        (is (= "year" (get-in @captured-ctx [:parameters :filter-by :fields 0 :field]))
            "Explicit params should override configured params on key conflicts")))))

(deftest test-execute-sub-skill-applies-per-skill-params
  (testing "execute-sub-skill forwards per-skill params into context parameters"
    (let [captured-ctx (atom nil)]
      (with-redefs [ctx/build-execution-context
                    (fn [_skill-id _inputs opts]
                      (let [fake-ctx {:inputs {} :parameters (:parameters opts) :services {} :skill-params {}}]
                        (reset! captured-ctx fake-ctx)
                        fake-ctx))
                    skills/execute-skill
                    (fn [_skill-id _exec-ctx]
                      {:outputs {}})]
        (tools/execute-sub-skill
          :builtin/rerank
          {:chunks [] :query "q" :docs-collection "docs"}
          {:tenant "t"
           :environment "e"
           :skill-params {:builtin/rerank {:top-k 120
                                           :context-top-k 25}}}
          nil)
        (is (= 120 (get-in @captured-ctx [:parameters :top-k])))
        (is (= 25 (get-in @captured-ctx [:parameters :context-top-k])))))))

(deftest test-generate-response-stores-citation-validation
  (testing "generate_response stores citation-validation in workspace"
    (let [!ws (workspace/create-workspace)
          fake-cv {:valid-indices #{1 2}
                   :invalid-indices #{}
                   :all-valid? true
                   :total-references 2
                   :verification-skipped? true
                   :response-changed? false}]
      (with-redefs [tools/execute-sub-skill
                    (fn [_skill-id _inputs _opts & _params]
                      {:outputs {:response "Answer [1] and [2]."
                                 :citations [{:index 1 :chunk-id "c1"}
                                             {:index 2 :chunk-id "c2"}]
                                 :citation-index {1 "c1" 2 "c2"}
                                 :citation-validation fake-cv}})]
        (tools/execute-tool-call
          "generate_response"
          {:query "test question"}
          !ws
          {:docs-collection "docs"
           :chunks-collection "chunks"
           :phrases-collection "phrases"
           :conversation-history []
           :opts {:tenant "t" :environment "e"}})
        (is (= fake-cv (:citation-validation @!ws)))))))

(deftest test-generate-response-prefers-reading-unread-hits-before-research
  (testing "The sufficiency gate prefers reading unread hits before starting a new search"
    (let [!ws (workspace/create-workspace)]
      (workspace/record-search!
       !ws
       {:queries ["årsverk Digdir 2022"]
        :chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 1 :content_length 50
                  "docs" {:total_chunks 4}}
                 {:chunk_id "c2" :doc_num "d1" :chunk_index 2 :content_length 50
                  "docs" {:total_chunks 4}}]
        :new-count 2
        :attribution {}
        :fallback? false})
      (workspace/record-read!
       !ws
       {:chunk-ids ["c1"]}
       [{:chunk_id "c1" :content_markdown "already read" :content_length 12}])
      (workspace/add-chunks-to-workspace! !ws [{:chunk_id "c1" :content_markdown "already read" :content_length 12}])
      (let [summary (sufficiency/build-evidence-summary @!ws "årsverk Digdir 2022")
            decision (sufficiency/evaluate-sufficiency "årsverk Digdir 2022" summary)]
        (is (= :insufficient (:status decision)))
        (is (= :read-more (:suggested-strategy decision)))
        (is (= ["c2"] (:unread-chunk-ids summary)))
        (is (some #(re-find #"exact reported value" %) (:missing-info decision)))))))

(deftest test-generate-response-targets-research-after-current-hits-exhausted
  (testing "The sufficiency gate switches to re-search once the current hits are exhausted"
    (let [!ws (workspace/create-workspace)]
      (workspace/record-search!
       !ws
       {:queries ["årsverk Digdir 2022"]
        :chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 0 :content_length 50
                  "docs" {:total_chunks 1}}]
        :new-count 1
        :attribution {}
        :fallback? false})
      (workspace/record-read!
       !ws
       {:chunk-ids ["c1"]}
       [{:chunk_id "c1" :content_markdown "already read" :content_length 12}])
      (workspace/add-chunks-to-workspace! !ws [{:chunk_id "c1" :content_markdown "already read" :content_length 12}])
      (let [summary (sufficiency/build-evidence-summary @!ws "årsverk Digdir 2022")
            decision (sufficiency/evaluate-sufficiency "årsverk Digdir 2022" summary)]
        (is (= :insufficient (:status decision)))
        (is (= :re-search (:suggested-strategy decision)))
        (is (empty? (:unread-chunk-ids summary)))
        (is (some #(re-find #"exact reported value" %) (:missing-info decision)))))))

(deftest test-generate-response-targets-research-when-unread-hits-do-not-fit-budget
  (testing "A large unread candidate still keeps the gate in re-search mode when no exact value is present"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc :budget-limits {:max-search-passes 4
                                       :max-read-operations 6
                                       :max-read-content-length 100})
      (workspace/record-search!
       !ws
       {:queries ["årsverk Digdir 2022"]
        :chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 0 :content_length 40
                  "docs" {:total_chunks 2}}
                 {:chunk_id "c2" :doc_num "d1" :chunk_index 1 :content_length 80
                  "docs" {:total_chunks 2}}]
        :new-count 2
        :attribution {}
        :fallback? false})
      (workspace/record-read!
       !ws
       {:chunk-ids ["c1"]}
       [{:chunk_id "c1" :content_markdown "already read" :content_length 40}])
      (workspace/add-chunks-to-workspace! !ws [{:chunk_id "c1" :content_markdown "already read" :content_length 40}])
      (let [summary (assoc (sufficiency/build-evidence-summary @!ws "årsverk Digdir 2022")
                           :unread-chunk-ids []
                           :unread-range nil)
            decision (sufficiency/evaluate-sufficiency "årsverk Digdir 2022" summary)]
        (is (= :insufficient (:status decision)))
        (is (= :re-search (:suggested-strategy decision)))))))

(deftest test-generate-response-does-not-recommend-search-when-search-budget-is-exhausted
  (testing "Workspace recording maps finalize decisions to legacy answer-with-uncertainty guidance"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc :budget-limits {:max-search-passes 1
                                       :max-read-operations 6
                                       :max-read-content-length 100})
      (workspace/record-search!
       !ws
       {:queries ["årsverk Digdir 2022"]
        :chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 0 :content_length 40
                  "docs" {:total_chunks 2}}
                 {:chunk_id "c2" :doc_num "d1" :chunk_index 1 :content_length 80
                  "docs" {:total_chunks 2}}]
        :new-count 2
        :attribution {}
        :fallback? false})
      (workspace/record-read!
       !ws
       {:chunk-ids ["c1"]}
       [{:chunk_id "c1" :content_markdown "already read" :content_length 40}])
      (workspace/add-chunks-to-workspace! !ws [{:chunk_id "c1" :content_markdown "already read" :content_length 40}])
      (workspace/record-sufficiency-decision!
       !ws
       {:query "årsverk Digdir 2022"
        :status :insufficient
        :reasoning "Search budget exhausted; finalize with uncertainty."
        :missing-info ["Need the exact reported value for \"årsverk\" in 2022."]
        :contradiction-detected? false
        :suggested-strategy :finalize})
      (is (= :finalize
             (-> @!ws :sufficiency-decisions first :action)))
      (is (= :answer-with-uncertainty
             (-> @!ws :sufficiency-decisions first :insufficiency :recommended-action))))))

(deftest test-generate-response-uses-conflict-status-and-guidance
  (testing "The sufficiency gate marks contradictory metric values as conflicting"
    (let [!ws (workspace/create-workspace)]
      (workspace/add-chunks-to-workspace!
       !ws
       [{:chunk_id "c1"
         :doc_num "d1"
         :chunk_index 0
         :content_markdown "Digdir hadde 326 utførte årsverk i 2022."
         :content_length 39}
        {:chunk_id "c2"
         :doc_num "d2"
         :chunk_index 0
         :content_markdown "Digdir hadde 356 utførte årsverk i 2022."
         :content_length 39}])
      (let [summary (sufficiency/build-evidence-summary @!ws "årsverk Digdir 2022")
            decision (sufficiency/evaluate-sufficiency "årsverk Digdir 2022" summary)]
        (is (= :conflicting (:status decision)))
        (is (= :re-search (:suggested-strategy decision)))
        (is (true? (:contradiction-detected? decision)))
        (workspace/record-sufficiency-decision!
         !ws
         (assoc decision :query "årsverk Digdir 2022"))
        (is (= :conflict
               (-> @!ws :sufficiency-decisions first :insufficiency :failure-type)))))))

(deftest test-sufficiency-asks-for-clarification-when-multiple-altinn-apis-match
  (testing "Broad Altinn API questions clarify instead of finalizing against whichever API docs were retrieved"
    (let [query "Hvilket API i Altinn 3 skal jeg bruke?"
          !ws (workspace/create-workspace)]
      (workspace/record-query-intent! !ws (workspace/infer-query-intent query []))
      (workspace/add-chunks-to-workspace!
       !ws
       [{:chunk_id "c1"
         :doc_num "d1"
         :chunk_index 0
         :title "About"
         :content_markdown "Altinn Formidling gir styrt filoverføring (Managed File Transfer - MFT)."
         :content_length 75}
        {:chunk_id "c2"
         :doc_num "d2"
         :chunk_index 0
         :title "About"
         :content_markdown "Altinn Melding brukes for å sende og motta meldinger i Altinn."
         :content_length 70}
        {:chunk_id "c3"
         :doc_num "d3"
         :chunk_index 0
         :title "Technical overview"
         :content_markdown "Altinn Broker bridge brukes i overgangsløsningen for å rute kall videre."
         :content_length 79}])
      (let [summary (sufficiency/build-evidence-summary @!ws query)
            decision (sufficiency/evaluate-sufficiency query summary)]
        (is (= :ask-clarification (:suggested-strategy decision)))
        (is (= :insufficient (:status decision)))
        (is (re-find #"Hvilket Altinn 3 API mener du"
                     (:clarification-question decision)))
        (is (some #{"Altinn Formidling API"} (:options decision)))
        (is (some #{"Altinn Melding API"} (:options decision)))))))

(deftest test-sufficiency-asks-for-clarification-when-authorization-scope-is-broad
  (testing "Broad Altinn authorization questions clarify instead of collapsing app, resource, and delegation scopes"
    (let [query "Hvordan fungerer autorisasjon i Altinn 3?"
          !ws (workspace/create-workspace)]
      (workspace/record-query-intent! !ws (workspace/infer-query-intent query []))
      (workspace/add-chunks-to-workspace!
       !ws
       [{:chunk_id "c1"
         :doc_num "d1"
         :chunk_index 0
         :title "Rules"
         :content_markdown "Autorisasjon uttrykkes og håndteres ulikt for Altinn Apps og Altinn 3-ressurser."
         :content_length 88}
        {:chunk_id "c2"
         :doc_num "d2"
         :chunk_index 0
         :title "Rules"
         :content_markdown "Tilgang til tjenester i Altinn styres gjennom fullmakter og delegering."
         :content_length 74}])
      (let [summary (sufficiency/build-evidence-summary @!ws query)
            decision (sufficiency/evaluate-sufficiency query summary)]
        (is (= :ask-clarification (:suggested-strategy decision)))
        (is (some #{"Altinn-apper"} (:options decision)))
        (is (some #{"Altinn-ressurser"} (:options decision)))
        (is (some #{"Fullmakter/delegering"} (:options decision)))))))

(deftest test-sufficiency-does-not-treat-incidental-procedural-numbers-as-conflicts
  (testing "Broad publishing questions avoid fake numeric conflicts and ask for scope clarification instead"
    (let [query "Hvordan publiserer jeg i Altinn 3?"
          !ws (workspace/create-workspace)]
      (workspace/record-query-intent! !ws (workspace/infer-query-intent query []))
      (workspace/add-chunks-to-workspace!
       !ws
       [{:chunk_id "c1"
         :doc_num "d1"
         :chunk_index 0
         :title "Getting started"
         :content_markdown "1. Ha en eksisterende Altinn 2 Formidlingstjeneste. 2. Ha eller lage en tilsvarende tjeneste i Altinn 3."
         :content_length 110}
        {:chunk_id "c2"
         :doc_num "d2"
         :chunk_index 0
         :title "Altinn apps"
         :content_markdown "Altinn Apps er infrastrukturen for applikasjoner laget i Altinn Studio."
         :content_length 76}])
      (let [summary (sufficiency/build-evidence-summary @!ws query)
            decision (sufficiency/evaluate-sufficiency query summary)]
        (is (not= :conflicting (:status decision)))
        (is (= :ask-clarification (:suggested-strategy decision)))
        (is (some #{"Altinn Apps"} (:options decision)))
        (is (some #{"Altinn Formidling"} (:options decision)))))))

(deftest test-sufficiency-asks-for-clarification-for-broad-altinn-developer-onboarding
  (testing "Broad Altinn developer onboarding questions clarify between multiple product workflows"
    (let [query "Hvordan kommer jeg i gang med Altinn 3 som utvikler?"
          !ws (workspace/create-workspace)]
      (workspace/record-query-intent! !ws (workspace/infer-query-intent query []))
      (workspace/add-chunks-to-workspace!
       !ws
       [{:chunk_id "c1"
         :doc_num "d1"
         :chunk_index 0
         :title "Getting started"
         :content_markdown "Altinn Studio brukes for å bygge og konfigurere tjenester."
         :content_length 63}
        {:chunk_id "c2"
         :doc_num "d2"
         :chunk_index 0
         :title "Altinn apps"
         :content_markdown "Altinn Apps er en skalerbar infrastruktur for applikasjoner laget i Altinn Studio."
         :content_length 85}
        {:chunk_id "c3"
         :doc_num "d3"
         :chunk_index 0
         :title "About"
         :content_markdown "Altinn Formidling brukes for sikker filoverføring i Altinn 3."
         :content_length 66}])
      (let [summary (sufficiency/build-evidence-summary @!ws query)
            decision (sufficiency/evaluate-sufficiency query summary)]
        (is (= :ask-clarification (:suggested-strategy decision)))
        (is (>= (count (:options decision)) 2))
        (is (re-find #"Hvilken del av Altinn 3 mener du"
                     (:clarification-question decision)))))))

(deftest test-search-budget-is-enforced
  (testing "search refuses additional calls once budget is exhausted"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc :budget-limits {:max-search-passes 1
                                       :max-read-operations 5
                                       :max-read-content-length 5000})
      (workspace/record-search!
       !ws
       {:queries ["q1"]
        :chunks []
        :new-count 0
        :attribution {}
        :fallback? false})
      (let [result (tools/execute-tool-call
                    "search"
                    {:queries ["q2"]}
                    !ws
                    {:docs-collection "docs"
                     :chunks-collection "chunks"
                     :phrases-collection "phrases"
                     :conversation-history []
                     :opts {:tenant "t" :environment "e"}})]
        (is (re-find #"Search budget exhausted" result))
        (is (= 1 (count (:search-history @!ws))))))))

(deftest test-search-filter-fallback-error-keeps-filtered-zero-hit-result
  (testing "If the broadened retry fails, the filtered zero-hit search is still recorded and surfaced"
    (let [!ws (workspace/create-workspace)
          calls (atom [])]
      (with-redefs [tools/execute-sub-skill
                    (fn [_skill-id _inputs _opts & [params]]
                      (swap! calls conj params)
                      (if (:filter-by params)
                        {:outputs {:chunks []
                                   :search-attribution {:auto-filter-applied {:fields [{:field "owner_short"
                                                                                       :selected-options ["Digdir"]}]}}}}
                        {:error {:error-type :infra-unavailable
                                 :error-message "Connection refused"
                                 :error-data {:service :typesense}}}))]
        (let [result (tools/execute-tool-call
                       "search"
                       {:queries ["årsverk Digdir 2022"]
                        :filter_by {:fields [{:field "owner_short"
                                              :selected_options ["Digdir"]}]}}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (re-find #"Search pass 1: found 0 chunks" result))
          (is (re-find #"retry without filters failed: Connection refused" result))
          (is (= 2 (count @calls)))
          (is (= 1 (count (:search-history @!ws))))
          (is (= 1 (count (:search-errors @!ws))))
          (is (= ["årsverk Digdir 2022"]
                 (-> @!ws :search-history first :queries)))
          (is (false? (-> @!ws :search-history first :fallback?)))
          (is (true? (-> @!ws :search-errors first :fallback?)))
          (is (= :infra-unavailable
                 (-> @!ws :search-errors first :error-type))))))))

(deftest test-read-budget-is-enforced
  (testing "read_chunks refuses additional calls once read budget is exhausted"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc :budget-limits {:max-search-passes 4
                                       :max-read-operations 1
                                       :max-read-content-length 10})
      (workspace/record-read!
       !ws
       {:chunk-ids ["c1"]}
       [{:chunk_id "c1" :content_markdown "already read" :content_length 12}])
      (let [result (tools/execute-tool-call
                    "read_chunks"
                    {:chunk_ids ["c2"]}
                    !ws
                    {:docs-collection "docs"
                     :chunks-collection "chunks"
                     :phrases-collection "phrases"
                     :conversation-history []
                     :opts {:tenant "t" :environment "e"}})]
        (is (re-find #"Read budget exhausted" result))
        (is (= 1 (count (:read-history @!ws))))))))

(deftest test-next-research-suggestions-do-not-repeat-latest-batch-verbatim
  (testing "Targeted re-search suggestions are concrete and differ from the latest search batch"
    (let [workspace {:search-history [{:queries ["årsverk Digdir 2022"
                                                 "antall ansatte Digdir 2022"]}]
                     :read-history [{:returned-chunk-ids ["c1" "c2"]}]}
          suggestions (workspace/next-research-suggestions
                       workspace
                       "Hvor mange årsverk hadde Digdir i 2022?"
                       "Det er ikke nok informasjon til å finne eksakt tall.")]
      (is (seq suggestions))
      (is (every? string? suggestions))
      (is (not-any? #{"årsverk digdir 2022"
                      "antall ansatte digdir 2022"}
                    suggestions))
      (is (some #(or (str/includes? % "eksakt tall")
                     (str/includes? % "oppgitt tall")
                     (str/includes? % "reported")
                     (str/includes? % "fte"))
                suggestions)))))

(deftest test-format-research-guidance-notes-low-search-budget
  (testing "Low remaining search budget narrows the guidance"
    (let [workspace {:budget-limits {:max-search-passes 2
                                     :max-read-operations 5
                                     :max-read-content-length 5000}
                     :search-history [{:queries ["årsverk Digdir 2022"]}]
                     :read-history [{:returned-chunk-ids ["c1"]}]
                     :last-query-intent {:answer-type :numeric-fact
                                         :entity "Digdir"
                                         :year-or-date "2022"
                                         :metric "årsverk"
                                         :doc-family-preference "årsrapport"}
                     :last-insufficiency {:failure-type :missing-numeric-fact
                                          :target-entity "Digdir"
                                          :target-year "2022"
                                          :target-metric "årsverk"
                                          :doc-family-hint "årsrapport"}}
          guidance (workspace/format-research-guidance
                    workspace
                    "Hvor mange årsverk hadde Digdir i 2022?"
                    "Fant ikke et eksakt tall.")]
      (is (re-find #"Budget is low" guidance))
      (is (re-find #"canonical document families" guidance)))))

(deftest test-next-research-suggestions-use-structured-missing-numeric-fact
  (testing "Structured insufficiency steers re-search toward metric-specific and canonical-source queries"
    (let [workspace {:search-history [{:queries ["årsverk Digdir 2022"]
                                       :chunk-summaries [{:chunk_id "c1"
                                                          :title "HR og bemanning"
                                                          :headers "{:header \"Bemanning\"}"}]}]
                     :read-history [{:returned-chunk-ids ["c1"]}]
                     :last-query-intent {:answer-type :numeric-fact
                                         :entity "Digdir"
                                         :year-or-date "2022"
                                         :metric "årsverk"
                                         :doc-family-preference "årsrapport"
                                         :scope-signals [:year-bounded]}
                     :last-insufficiency {:failure-type :missing-numeric-fact
                                          :target-entity "Digdir"
                                          :target-year "2022"
                                          :target-metric "årsverk"
                                          :doc-family-hint "årsrapport"}} 
          suggestions (workspace/next-research-suggestions
                       workspace
                       "Hvor mange årsverk hadde Digdir i 2022?"
                       "Fant ikke et eksakt tall.")]
      (is (some #{"digdir årsrapport 2022 årsverk"} suggestions))
      (is (some #(str/includes? % "oppgitt tall") suggestions)))))

(deftest test-next-research-suggestions-can-pivot-doc-family
  (testing "Wrong-doc-family insufficiency suggests canonical document-family searches"
    (let [workspace {:search-history [{:queries ["bemanning Digdir 2022"]
                                       :chunk-summaries [{:chunk_id "c1"
                                                          :title "HR og bemanning"
                                                          :headers "{:header \"Bemanning\"}"}]}]
                     :read-history [{:returned-chunk-ids ["c1"]}]
                     :last-query-intent {:answer-type :numeric-fact
                                         :entity "Digdir"
                                         :year-or-date "2022"
                                         :metric "årsverk"
                                         :doc-family-preference "årsrapport"
                                         :scope-signals [:year-bounded]}
                     :last-insufficiency {:failure-type :wrong-doc-family
                                          :target-entity "Digdir"
                                          :target-year "2022"
                                          :target-metric "årsverk"
                                          :doc-family-hint "årsrapport"}} 
          suggestions (workspace/next-research-suggestions
                       workspace
                       "Hvor mange årsverk hadde Digdir i 2022?"
                       "Kildene ser ut til å være fra feil dokumenttype.")]
      (is (some #{"digdir årsrapport 2022 årsverk"} suggestions))
      (is (some #(or (str/includes? % "årsrapport")
                     (str/includes? % "årsmelding"))
                suggestions)))))

(deftest test-next-research-suggestions-handle-conflict
  (testing "Conflict insufficiency suggests disambiguating summary-oriented searches"
    (let [workspace {:search-history [{:queries ["årsverk Digdir 2022"]
                                       :chunk-summaries [{:chunk_id "c1"
                                                          :title "Hovudtal"
                                                          :headers "{:header \"Hovudtal\"}"}]}]
                     :read-history [{:returned-chunk-ids ["c1"]}]
                     :last-query-intent {:answer-type :numeric-fact
                                         :entity "Digdir"
                                         :year-or-date "2022"
                                         :metric "årsverk"
                                         :doc-family-preference "årsrapport"
                                         :scope-signals [:year-bounded]}
                     :last-insufficiency {:failure-type :conflict
                                          :target-entity "Digdir"
                                          :target-year "2022"
                                          :target-metric "årsverk"
                                          :doc-family-hint "årsrapport"}} 
          suggestions (workspace/next-research-suggestions
                       workspace
                       "Hvor mange årsverk hadde Digdir i 2022?"
                       "Kildene inneholder motstridende tall.")]
      (is (some #(or (str/includes? % "presisering")
                     (str/includes? % "hovudtal"))
                suggestions))
      (is (not-any? #{"årsverk digdir 2022"} suggestions)))))

(deftest test-plan-queries-merges-intent-aware-variants-into-workspace
  (testing "plan_queries stores intent-aware query variants alongside planner output"
    (let [!ws (workspace/create-workspace)]
      (with-redefs [tools/execute-sub-skill
                    (fn [skill-id inputs _opts]
                      (is (= :builtin/query-planner skill-id))
                      (is (= "Hvor mange årsverk hadde Digdir i 2022?" (:query inputs)))
                      {:outputs {:queries ["digdir 2022 bemanning"]}})]
        (let [result (tools/execute-tool-call
                      "plan_queries"
                      {:query "Hvor mange årsverk hadde Digdir i 2022?"}
                      !ws
                      {:docs-collection "docs"
                       :chunks-collection "chunks"
                       :phrases-collection "phrases"
                       :conversation-history []
                       :opts {:tenant "t" :environment "e"}})]
          (is (re-find #"Generated" result))
          (is (some #{"årsverk digdir 2022"} (:queries @!ws)))
          (is (some #{"utførte årsverk digdir 2022"} (:queries @!ws)))
          (is (some #{"digdir årsrapport 2022 årsverk"} (:queries @!ws)))
          (is (some #{"digdir 2022 bemanning"} (:queries @!ws))))))))

;; =============================================================================
;; Slice 23 — agent threads :user-intent through plan_queries → search
;; =============================================================================

(deftest test-plan-queries-stashes-user-intent-for-subsequent-search
  (testing "plan_queries captures :user-intent into workspace; subsequent search passes it to retrieval"
    (let [!ws (workspace/create-workspace)
          captured-search-inputs (atom nil)]
      (with-redefs [tools/execute-sub-skill
                    (fn [skill-id inputs _opts & _]
                      (cond
                        (= :builtin/query-planner skill-id)
                        {:outputs {:queries ["dialogporten api"
                                             "dialogporten meldingsformidling"]
                                   :user-intent "hva er dialogporten"}}

                        (= :builtin/retrieval skill-id)
                        (do (reset! captured-search-inputs inputs)
                            {:outputs {:chunks []
                                       :search-attribution {}}})

                        :else {:outputs {}}))]
        ;; Step 1: plan_queries — should store :last-user-intent on workspace.
        (tools/execute-tool-call
          "plan_queries"
          {:query "What is Dialogporten?"}
          !ws
          {:docs-collection "docs"
           :chunks-collection "chunks"
           :phrases-collection "phrases"
           :conversation-history []
           :opts {:tenant "t" :environment "e"}})
        (is (= "hva er dialogporten" (:last-user-intent @!ws))
            "Workspace should hold the planner's :user-intent")

        ;; Step 2: search — should pass :user-intent to retrieval inputs.
        (tools/execute-tool-call
          "search"
          {:queries ["dialogporten api" "dialogporten meldingsformidling"]}
          !ws
          {:docs-collection "docs"
           :chunks-collection "chunks"
           :phrases-collection "phrases"
           :conversation-history []
           :opts {:tenant "t" :environment "e"}})
        (is (= "hva er dialogporten" (:user-intent @captured-search-inputs))
            "Retrieval should receive :user-intent in its inputs")))))

(deftest test-search-without-prior-plan-queries-omits-user-intent
  (testing "When no plan_queries ran first, search-inputs has no :user-intent"
    (let [!ws (workspace/create-workspace)
          captured-search-inputs (atom nil)]
      (with-redefs [tools/execute-sub-skill
                    (fn [_skill-id inputs _opts & _]
                      (reset! captured-search-inputs inputs)
                      {:outputs {:chunks [] :search-attribution {}}})]
        (tools/execute-tool-call
          "search"
          {:queries ["raw query"]}
          !ws
          {:docs-collection "docs"
           :chunks-collection "chunks"
           :phrases-collection "phrases"
           :conversation-history []
           :opts {:tenant "t" :environment "e"}})
        (is (nil? (:user-intent @captured-search-inputs))
            "Without plan_queries, no :user-intent should be set on retrieval inputs")))))

;; =============================================================================
;; Conversation History Tests
;; =============================================================================

(deftest test-build-initial-messages-includes-history
  (testing "Builds initial messages with previous turns before current query"
    (let [messages (loop/build-initial-messages
                    "system"
                    "Hvordan fordeler de seg?"
                    [{:message/role :user :message/text "Hvor mange årsverk hadde Digdir i 2022?"}
                     {:message/role :assistant :message/text "Digdir hadde 360 årsverk i 2022."}])]
      (is (= "system" (get-in messages [0 :content])))
      (is (= "user" (get-in messages [1 :role])))
      (is (= "Hvor mange årsverk hadde Digdir i 2022?" (get-in messages [1 :content])))
      (is (= "assistant" (get-in messages [2 :role])))
      (is (= "Digdir hadde 360 årsverk i 2022." (get-in messages [2 :content])))
      (is (= "Hvordan fordeler de seg?" (get-in messages [3 :content]))))))

(deftest test-build-initial-messages-dedups-current-query
  (testing "Does not duplicate current query if already the last history message"
    (let [messages (loop/build-initial-messages
                    "system"
                    "Hvordan fordeler de seg?"
                    [{:role :user :text "Hvor mange årsverk hadde Digdir i 2022?"}
                     {:role :assistant :text "Digdir hadde 360 årsverk i 2022."}
                     {:role :user :text "Hvordan fordeler de seg?"}])]
      (is (= 4 (count messages)))
      (is (= "Hvordan fordeler de seg?" (get-in messages [3 :content]))))))

(deftest test-agentic-loop-handles-llm-exception
  (testing "Returns structured error instead of throwing when call-llm fails"
    (let [!ws (workspace/create-workspace)]
      (with-redefs [loop/call-llm
                    (th/llm-throwing "Interceptor Exception: status: 400" {:status 400})]
        (let [result (loop/agentic-loop
                       [{:role "system" :content "s"}
                        {:role "user" :content "q"}]
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}}
                       {:max-iterations 1})]
          (is (string? (:error result)))
          (is (re-find #"status 400" (:error result)))
          (is (vector? (:trace result))))))))

(deftest test-agentic-loop-normalizes-tool-call-messages
  (testing "Normalizes assistant tool call replay payload before next LLM call"
    (let [!ws (workspace/create-workspace)
          calls (atom [])]
      (with-redefs [loop/call-llm
                    (fn [_tenant messages tools _model _temperature & _opts]
                      (swap! calls conj {:messages messages :tools tools})
                      (if (= 1 (count @calls))
                        {:choices [{:finish_reason "tool_calls"
                                    :message {:content nil
                                              :tool_calls [{:id "call_1"
                                                            :index 0
                                                            :type "function"
                                                            :function {:name "plan_queries"
                                                                       :arguments "{\"query\":\"x\"}"}}]}}]}
                        {:choices [{:finish_reason "stop"
                                    :message {:content "done"}}]}))
                    tools/execute-tool-call (tu/recording-fn "ok")]
        (let [result (loop/agentic-loop
                       [{:role "system" :content "s"}
                        {:role "user" :content "q"}]
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}}
                       {:max-iterations 3})
              second-call-messages (:messages (second @calls))
              assistant-msg (first (filter #(= "assistant" (:role %)) second-call-messages))
              replayed-tool-call (first (:tool_calls assistant-msg))]
          (is (= "done" (:response result)))
          (is (= "" (:content assistant-msg)))
          (is (nil? (:index replayed-tool-call)))
          (is (= "function" (:type replayed-tool-call)))
          (is (string? (get-in replayed-tool-call [:function :arguments]))))))))

(deftest test-agentic-loop-prefers-last-generated-response-on-exhaustion
  (testing "Returns the last sufficient generate_response output instead of failing on fallback"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc
             :last-generated-response "Altinn 3 launch year is not stated in the sources."
             :last-generate-insufficient-context false
             :iteration-history [{:iteration 9
                                  :reasoning nil
                                  :tool-calls [{:tool "generate_response"
                                                :args {:query "Når ble Altinn 3 lansert?"}
                                                :result-summary "Altinn 3 launch year is not stated in the sources."}]}])
      (with-redefs [loop/call-llm
                    (th/llm-throwing "Interceptor Exception: status: 400" {:status 400})]
        (let [result (loop/agentic-loop
                       [{:role "system" :content "s"}
                        {:role "user" :content "q"}]
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}}
                       {:max-iterations 0})]
          (is (= "Altinn 3 launch year is not stated in the sources."
                 (:response result)))
          (is (true? (:exhausted result)))
          (is (nil? (:error result)))
          (is (= 1 (count (:trace result)))))))))

(deftest test-agentic-loop-exits-with-clarification-request
  (testing "Ask-clarification decisions from the live sufficiency call path stop the loop with a dedicated clarification payload"
    (let [!ws (workspace/create-workspace)]
      (with-redefs [loop/call-llm
                    (fn [_tenant _messages _tools _model _temperature & _opts]
                      {:choices [{:finish_reason "tool_calls"
                                  :message {:content "I need to check whether the question is ambiguous."
                                            :tool_calls [{:id "call_1"
                                                          :type "function"
                                                          :function {:name "generate_response"
                                                                     :arguments "{\"query\":\"Which Digdir report do you mean?\"}"}}]}}]})
                    tools/execute-tool-call (tu/recording-fn "Draft answer")
                    sufficiency/build-evidence-summary (fn [& _] {:query "q"})
                    sufficiency/evaluate-sufficiency
                    (fn [query evidence-summary {:keys [llm-fn model temperature] :as opts}]
                      (is (= "q" query))
                      (is (= {:query "q"} evidence-summary))
                      (is (fn? llm-fn))
                      (is (= nil model))
                      (is (= 0.0 temperature))
                      (is (contains? opts :llm-fn))
                      {:status :insufficient
                       :reasoning "The query could refer to multiple reports."
                       :missing-info ["Need the report year."]
                       :suggested-strategy :ask-clarification
                       :clarification-question "Which Digdir report do you mean: 2022 or 2023?"
                       :options ["2022" "2023"]
                       :context-summary "The available evidence spans more than one Digdir report year."})]
        (let [result (loop/agentic-loop
                       [{:role "system" :content "s"}
                        {:role "user" :content "q"}]
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}}
                       {:max-iterations 3})]
          (is (= :clarify (:terminal-state result)))
          (is (= "Which Digdir report do you mean: 2022 or 2023?"
                 (get-in result [:clarification-request :question])))
          (is (= ["2022" "2023"]
                 (get-in result [:clarification-request :options])))
          (is (= "Which Digdir report do you mean: 2022 or 2023?"
                 (:response result))))))))

(deftest test-agentic-loop-skips-redundant-response-validation-after-decisive-read-signal
  (testing "Simple lookup runs finalize directly after generate_response when read signals already settled sufficiency"
    (let [!ws (workspace/create-workspace)
          llm-calls (atom 0)
          validation-calls (atom 0)]
      (swap! !ws assoc
             :last-query-intent {:answer-type :lookup}
             :last-read-signal {:status :support-found
                                :scope-assessment :aligned
                                :degraded? false
                                :evaluation-mode :llm}
             :open-evidence-gaps []
             :evidence-contradictions []
             :shadow-sufficiency-decisions [{:source :read-signals
                                            :status :sufficient
                                            :suggested-strategy :finalize
                                            :reason-code :all-critical-claims-covered}])
      (with-redefs [loop/call-llm
                    (fn [_tenant _messages _tools _model _temperature & _opts]
                      (swap! llm-calls inc)
                      {:choices [{:finish_reason "tool_calls"
                                  :message {:content "I have enough evidence and will draft the answer."
                                            :tool_calls [{:id "call_1"
                                                          :type "function"
                                                          :function {:name "generate_response"
                                                                     :arguments "{\"query\":\"q\"}"}}]}}]})
                    ;; Seam under both dispatch paths (#91); the real code
                    ;; builds the timing envelope around this result text.
                    tools/execute-tool-call*
                    (fn [tool-name _args !workspace _ambient-ctx]
                      (is (= "generate_response" tool-name))
                      (swap! !workspace assoc :last-generated-response "Svar med støtte [1].")
                      "Svar med støtte [1].")
                    sufficiency/evaluate-sufficiency
                    (fn [& _]
                      (swap! validation-calls inc)
                      {:status :sufficient
                       :reasoning "should not run"
                       :suggested-strategy :finalize})]
        (let [result (loop/agentic-loop
                       [{:role "system" :content "s"}
                        {:role "user" :content "q"}]
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}}
                       {:max-iterations 3})]
          (is (= :finalize (:terminal-state result)))
          (is (= "Svar med støtte [1]." (:response result)))
          (is (= 1 @llm-calls))))
      (is (= 0 @validation-calls))
      (is (empty? (:response-validations @!ws)))
      (is (empty? (filter #(contains? #{:sufficiency-gate :response-validation} (:stage %))
                          (:stage-timings @!ws)))))))

(deftest test-agentic-loop-falls-back-to-last-generated-response-when-final-fallback-call-fails
  (testing "Returns the latest generated response instead of surfacing a fallback LLM transport error"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc
             :last-generated-response "Kildene oppgir ikke når Altinn 3 ble lansert."
             :last-generate-insufficient-context true
             :iteration-history [{:iteration 9
                                  :reasoning nil
                                  :tool-calls [{:tool "generate_response"
                                                :args {:query "Når ble Altinn 3 lansert?"}
                                                :result-summary "Kildene oppgir ikke når Altinn 3 ble lansert."}]}])
      (with-redefs [loop/call-llm
                    (th/llm-throwing "Interceptor Exception: status: 400" {:status 400})]
        (let [result (loop/agentic-loop
                       [{:role "system" :content "s"}
                        {:role "user" :content "q"}
                        {:role "assistant" :content ""}]
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}}
                       {:max-iterations 0})]
          (is (= "Kildene oppgir ikke når Altinn 3 ble lansert."
                 (:response result)))
          (is (true? (:exhausted result)))
          (is (nil? (:error result))))))))

(deftest test-build-fallback-messages-drops-blank-assistant-turns
  (testing "Fallback messages omit empty assistant/tool-call turns that are invalid without tool_calls"
    (let [messages [{:role "system" :content "system prompt"}
                    {:role "user" :content "Når ble Altinn 3 lansert?"}
                    {:role "assistant" :content ""}
                    {:role "assistant" :content "Jeg fant ikke lanseringsdatoen i de leste kildene."}]
          fallback-msg {:role "user" :content "Svar nå uten flere verktøy."}
          fallback-messages (#'loop/build-fallback-messages messages fallback-msg)]
      (is (= [{:role "system" :content "system prompt"}
              {:role "user" :content "Når ble Altinn 3 lansert?"}
              {:role "assistant" :content "Jeg fant ikke lanseringsdatoen i de leste kildene."}
              {:role "user" :content "Svar nå uten flere verktøy."}]
             fallback-messages)))))

;; =============================================================================
;; Search Metadata Formatting Tests
;; =============================================================================

(deftest test-format-search-metadata-results
  (testing "Formats metadata-only search results without content"
    (let [chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                   :content_length 500 :content_markdown "should not appear"
                   :retrieval-prior 4.2 :original-rank 1
                   :hit-count 3 :search-types #{:phrase :content}
                   :metadata "{:headers [\"## Section A\"]}"
                   "docs_coll" {:title "Test Doc" :url "/test" :total_chunks 5}}
                  {:chunk_id "c2" :doc_num "d1" :chunk_index 1
                   :content_length 300 :content_markdown "also hidden"
                   :hit-count 1
                   :metadata ""
                   "docs_coll" {:title "Test Doc" :url "/test" :total_chunks 5}}]
          formatted (tools/format-search-metadata-results chunks 2 2 1 nil)]
      (is (re-find #"Search pass 1: found 2 chunks" formatted))
      (is (re-find #"chunk_id=c1" formatted))
      (is (re-find #"chunk_index=0/5" formatted))
      (is (re-find #"content_length=500" formatted))
      (is (re-find #"score=4.200" formatted))
      (is (re-find #"rank=1" formatted))
      (is (not (re-find #"should not appear" formatted))))))

(deftest test-format-trace-file-quality-warning-found-vs-not-found
  (testing "Trace includes quality warning when reasoning claims success but response says not found"
    (let [trace-text (#'core/format-trace-file
                       {:query "q"
                        :model "m"
                        :trace [{:iteration 6
                                 :reasoning "Jeg har funnet informasjon om antall årsverk."
                                 :tool-calls [{:tool "generate_response"
                                               :args {:query "q"}
                                               :result-summary "generated"}]}]
                        :queries ["q"]
                        :chunks-count 1
                        :status :success
                        :response "Basert på dokumentasjonen er det ikke oppgitt et eksakt antall årsverk."
                        :workspace-chunks []
                        :citations []
                        :citation-index {}
                        :initial-messages []})]
      (is (re-find #"== TRACE QUALITY CHECKS ==" trace-text))
      (is (re-find #"\[warning\] found-vs-not-found" trace-text)))))

;; =============================================================================
;; read_chunks Tests
;; =============================================================================

(deftest test-read-chunks-by-ids
  (testing "read_chunks by chunk_ids always fetches full content from Typesense"
    (let [!ws (workspace/create-workspace)
          fetched-ids (atom [])]
      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [_docs _chunks id-list _opts]
                      (reset! fetched-ids (mapv :chunk_id id-list))
                      [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                        :content_markdown "Full content A" :content_length 14}
                       {:chunk_id "c2" :doc_num "d1" :chunk_index 1
                        :content_markdown "Full content B" :content_length 14}])]
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:chunk_ids ["c1" "c2"]}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (re-find #"Read 2 chunks" result))
          (is (re-find #"Full content A" result))
          (is (re-find #"Full content B" result))
          ;; ALL chunk_ids must be sent to Typesense (no cache bypass)
          (is (= ["c1" "c2"] @fetched-ids))
          (is (= 2 (count (:chunks @!ws)))))))))

(deftest test-read-chunks-skips-previously-non-supporting-chunk-ids
  (testing "read_chunks suppresses rereads of chunk ids that already produced zero support for the current query"
    (let [!ws (workspace/create-workspace)
          fetched-ids (atom [])]
      (swap! !ws assoc :non-supporting-chunk-ids #{"c1"})
      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [_docs _chunks id-list _opts]
                      (reset! fetched-ids (mapv :chunk_id id-list))
                      [{:chunk_id "c2" :doc_num "d1" :chunk_index 1
                        :content_markdown "Fresh content B" :content_length 15}])
                    read-signals/evaluate-read
                    (fn [_query _query-intent _plan _open-claims chunks _opts]
                      {:status :gap-remaining
                       :scope-assessment :aligned
                       :supported-claims []
                       :remaining-gaps [{:claim-id :answer-bearing-evidence
                                         :critical? true
                                         :reason :not-addressed}]
                       :contradictions []
                       :next-action-hint :read-more
                       :confidence 0.3
                       :degraded? false
                       :evaluation-mode :llm
                       :chunk-ids (mapv :chunk_id chunks)})]
        (let [ambient-ctx {:docs-collection "docs"
                           :chunks-collection "chunks"
                           :phrases-collection "phrases"
                           :query "Når ble Altinn 3 lansert?"
                           :conversation-history []
                           :opts {:tenant "t" :environment "e"}}
              result (tools/execute-tool-call
                      "read_chunks"
                      {:chunk_ids ["c1" "c2"]}
                      !ws
                      ambient-ctx)
              _ (workspace/evaluate-pending-reads! !ws ambient-ctx 0 (:query ambient-ctx))]
          (is (= ["c2"] @fetched-ids))
          (is (re-find #"Skipped 1 previously non-supporting chunk" result))
          (is (re-find #"Fresh content B" result))
          (is (= #{"c1" "c2"} (:non-supporting-chunk-ids @!ws))))))))

(deftest test-read-chunks-surfaces-both-suppression-and-budget-when-mixed
  (testing "When some chunks are suppressed as non-supporting AND budget drops the rest, emit both notes so the LLM sees the real blocker"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc
             :non-supporting-chunk-ids #{"blocked"}
             :budget-limits {:max-search-passes 4
                             :max-read-operations 6
                             :max-read-content-length 12000}
             :read-content-length 11900)
      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [_docs _chunks _id-list _opts]
                      (throw (ex-info "should not fetch when all chunks dropped" {})))]
        (let [result (tools/execute-tool-call
                      "read_chunks"
                      {:chunk_ids ["blocked" "big-1" "big-2"]}
                      !ws
                      {:docs-collection "docs"
                       :chunks-collection "chunks"
                       :phrases-collection "phrases"
                       :query "Når ble Altinn 3 lansert?"
                       :conversation-history []
                       :opts {:tenant "t" :environment "e"}})]
          (is (re-find #"Skipped 1 previously non-supporting chunk" result)
              "must mention the suppressed chunk so the LLM doesn't retry it")
          (is (re-find #"Read budget exhausted for the requested read" result)
              "must also mention that budget blocked the remaining chunks")))))
  (testing "When all chunks are suppressed with plenty of budget remaining, only the skipped-note is emitted"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc
             :non-supporting-chunk-ids #{"a" "b"}
             :budget-limits {:max-search-passes 4
                             :max-read-operations 6
                             :max-read-content-length 12000})
      (let [result (tools/execute-tool-call
                    "read_chunks"
                    {:chunk_ids ["a" "b"]}
                    !ws
                    {:docs-collection "docs"
                     :chunks-collection "chunks"
                     :phrases-collection "phrases"
                     :query "Når ble Altinn 3 lansert?"
                     :conversation-history []
                     :opts {:tenant "t" :environment "e"}})]
        (is (re-find #"Skipped 2 previously non-supporting chunks" result))
        (is (not (re-find #"Read budget exhausted" result))
            "no budget message when budget isn't the blocker")))))

(deftest test-read-chunks-by-range
  (testing "read_chunks by doc_num + chunk_range calls retrieve-chunks-by-range"
    (let [!ws (workspace/create-workspace)
          range-args (atom nil)]
      (with-redefs [digdir.rag.core/retrieve-chunks-by-range
                    (fn [_docs _chunks doc-num from to _opts]
                      (reset! range-args {:doc-num doc-num :from from :to to})
                      [{:chunk_id "r1" :doc_num doc-num :chunk_index from
                        :content_markdown "Range content"}])]
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:doc_num "d1" :chunk_range {:from 2 :to 5}}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (re-find #"Read 1 chunks" result))
          (is (= "d1" (:doc-num @range-args)))
          (is (= 2 (:from @range-args)))
          (is (= 5 (:to @range-args))))))))

(deftest test-read-chunks-emits-structured-read-signal-json
  (testing "read_chunks wires the local evaluator output into workspace state without surfacing it in the tool text"
    (let [!ws (workspace/create-workspace)]
      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [_docs _chunks _id-list _opts]
                      [{:chunk_id "c1"
                        :doc_num "d1"
                        :chunk_index 0
                        :content_markdown "Use POST /subscriptions to create an event subscription."
                        :content_length 58}])]
        (let [ambient-ctx {:docs-collection "docs"
                           :chunks-collection "chunks"
                           :phrases-collection "phrases"
                           :query "How do I subscribe to Altinn events?"
                           :conversation-history []
                           :opts {:tenant "t"
                                  :environment "e"
                                  :read-signals-llm-fn
                                  (fn [_messages _tools _model _temperature]
                                    {:choices [{:message {:content
                                                          "{\"status\":\"gap-remaining\",\"scope_assessment\":\"aligned\",\"supported_claims\":[{\"claim_id\":\"topic-match\",\"support\":\"explicit\",\"chunk_ids\":[\"c1\"]}],\"remaining_gaps\":[{\"claim_id\":\"answer-bearing-evidence\",\"reason\":\"not-addressed\",\"critical\":true}],\"contradictions\":[],\"next_action_hint\":\"read-more\",\"confidence\":0.71}"}}]})}}
              result (tools/execute-tool-call
                      "read_chunks"
                      {:chunk_ids ["c1"]}
                      !ws
                      ambient-ctx)
              _ (workspace/evaluate-pending-reads! !ws ambient-ctx 0 (:query ambient-ctx))]
          (is (not (re-find #"Read signal:" result))
              "Read signals belong in traces/diagnostics, not the tool-result text")
          (is (not (re-find #"Read signal JSON:" result)))
          (is (= :gap-remaining (get-in @!ws [:last-read-signal :status])))
          (is (= false (get-in @!ws [:last-read-signal :degraded?])))
          (is (= :read-more (get-in @!ws [:last-read-signal :next-action-hint]))))))))

(deftest test-effective-read-range-prefers-local-unread-anchor
  (testing "Wide range requests are narrowed around the best unread chunk in the active search results"
    (let [workspace {:search-history [{:chunk-summaries [{:chunk-id "c1" :doc-num "d1" :chunk-index 4}
                                                         {:chunk_id "c2" :doc-num "d1" :chunk-index 8}
                                                         {:chunk_id "c3" :doc-num "d1" :chunk-index 9}]}]
                     :read-history [{:returned-chunk-ids ["c1"]}]}
          budget {:low-read-budget? false}]
      (is (= {:from 7 :to 9}
             (#'workspace/effective-read-range
              workspace "d1" {:from 0 :to 12} budget))))))

(deftest test-effective-read-range-tightens-further-when-budget-is-low
  (testing "Low read budget uses an even tighter local window"
    (let [workspace {:search-history [{:chunk-summaries [{:chunk-id "c2" :doc-num "d1" :chunk-index 8}]}]
                     :read-history []}
          budget {:low-read-budget? true}]
      (is (= {:from 8 :to 9}
             (#'workspace/effective-read-range
              workspace "d1" {:from 0 :to 12} budget))))))

(deftest test-effective-read-range-respects-remaining-content-budget
  (testing "Remaining read-char budget can tighten the range below the normal local window"
    (let [workspace {:search-history [{:chunk-summaries [{:chunk-id "c2" :doc-num "d1" :chunk-index 8 :content-length 20}
                                                         {:chunk-id "c3" :doc-num "d1" :chunk-index 9 :content-length 20}
                                                         {:chunk-id "c4" :doc-num "d1" :chunk-index 10 :content-length 20}]}]


                     :read-history []}
          budget {:low-read-budget? false
                  :read-content-length-remaining 50}]
      (is (= {:from 8 :to 9}
             (#'workspace/effective-read-range
              workspace "d1" {:from 0 :to 12} budget))))))

(deftest test-read-chunks-by-range-clamps-broad-window
  (testing "read_chunks narrows broad range requests before hitting storage"
    (let [!ws (workspace/create-workspace)
          range-args (atom nil)]
      (workspace/record-search!
       !ws
       {:queries ["test query"]
        :chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 2 :content_length 50
                  "docs" {:total_chunks 20}}
                 {:chunk_id "c2" :doc_num "d1" :chunk_index 8 :content_length 50
                  "docs" {:total_chunks 20}}
                 {:chunk_id "c3" :doc_num "d1" :chunk_index 9 :content_length 50
                  "docs" {:total_chunks 20}}]
        :new-count 3
        :attribution {}
        :fallback? false})
      (workspace/record-read!
       !ws
       {:chunk-ids ["c1"]}
       [{:chunk_id "c1" :content_markdown "already read" :content_length 12}])
      (with-redefs [digdir.rag.core/retrieve-chunks-by-range
                    (fn [_docs _chunks doc-num from to _opts]
                      (reset! range-args {:doc-num doc-num :from from :to to})
                      [{:chunk_id "r1" :doc_num doc-num :chunk_index from
                        :content_markdown "Range content"}])]
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:doc_num "d1" :chunk_range {:from 0 :to 12}}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (re-find #"Read 1 chunks" result))
          (is (= {:doc-num "d1" :from 7 :to 9} @range-args)))))))

(deftest test-range-read-preserves_budget_for_followup_read
  (testing "Budget-aware range narrowing leaves room for a follow-up targeted read"
    (let [!ws (workspace/create-workspace)
          range-args (atom nil)]
      (swap! !ws assoc :budget-limits {:max-search-passes 4
                                       :max-read-operations 6
                                       :max-read-content-length 120})
      (workspace/record-search!
       !ws
       {:queries ["test query"]
        :chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 2 :content_length 70
                  "docs" {:total_chunks 20}}
                 {:chunk_id "c2" :doc_num "d1" :chunk_index 8 :content_length 20
                  "docs" {:total_chunks 20}}
                 {:chunk_id "c3" :doc_num "d1" :chunk_index 9 :content_length 20
                  "docs" {:total_chunks 20}}
                 {:chunk_id "c4" :doc_num "d1" :chunk_index 10 :content_length 20
                  "docs" {:total_chunks 20}}
                 {:chunk_id "c5" :doc_num "d1" :chunk_index 11 :content_length 20
                  "docs" {:total_chunks 20}}]
        :new-count 5
        :attribution {}
        :fallback? false})
      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [_docs _chunks id-list _opts]
                      (mapv (fn [{:keys [chunk_id]}]
                              (case chunk_id
                                "c1" {:chunk_id "c1" :doc_num "d1" :chunk_index 2
                                      :content_markdown (apply str (repeat 70 "a"))
                                      :content_length 70}
                                "c5" {:chunk_id "c5" :doc_num "d1" :chunk_index 11
                                      :content_markdown (apply str (repeat 10 "b"))
                                      :content_length 10}))
                            id-list))
                    digdir.rag.core/retrieve-chunks-by-range
                    (fn [_docs _chunks doc-num from to _opts]
                      (reset! range-args {:doc-num doc-num :from from :to to})
                      (mapv (fn [idx]
                              {:chunk_id (str "r" idx) :doc_num doc-num :chunk_index idx
                               :content_markdown (apply str (repeat 20 "x"))
                               :content_length 20})
                            (range from (inc to))))]
        (tools/execute-tool-call
         "read_chunks"
         {:chunk_ids ["c1"]}
         !ws
         {:docs-collection "docs"
          :chunks-collection "chunks"
          :phrases-collection "phrases"
          :conversation-history []
          :opts {:tenant "t" :environment "e"}})
        (tools/execute-tool-call
         "read_chunks"
         {:doc_num "d1" :chunk_range {:from 0 :to 12}}
         !ws
         {:docs-collection "docs"
          :chunks-collection "chunks"
          :phrases-collection "phrases"
          :conversation-history []
          :opts {:tenant "t" :environment "e"}})
        (let [followup (tools/execute-tool-call
                        "read_chunks"
                        {:chunk_ids ["c5"]}
                        !ws
                        {:docs-collection "docs"
                         :chunks-collection "chunks"
                         :phrases-collection "phrases"
                         :conversation-history []
                         :opts {:tenant "t" :environment "e"}})]
          (is (= {:doc-num "d1" :from 8 :to 9} @range-args))
          (is (re-find #"Read 1 chunks" followup))
          (is (= 120 (:read-content-length @!ws))))))))

(deftest test-read-chunks-truncation
  (testing "read_chunks truncates LLM output but workspace gets full content"
    (let [!ws (workspace/create-workspace)
          long-content (apply str (repeat 200 "x"))]
      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [& _]
                      [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                        :content_markdown long-content :content_length (count long-content)}])]
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:chunk_ids ["c1"] :max_content_length 50}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          ;; LLM output should be truncated
          (is (re-find #"\[truncated\]" result))
          ;; Workspace should have full content
          (is (= (count long-content)
                 (count (:content_markdown (get (:chunks @!ws) "c1"))))))))))

(deftest test-read-chunks-always-fetches-full-content
  (testing "read_chunks always fetches from Typesense even for previously-seen chunk IDs"
    (let [!ws (workspace/create-workspace)
          ;; Pre-populate seen-search-chunk-ids as if search already returned this chunk
          _ (swap! !ws update :seen-search-chunk-ids conj "c1")
          retrieve-called (atom false)]
      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [& _]
                      (reset! retrieve-called true)
                      [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                        :content_markdown "Full content from Typesense" :content_length 27}])]
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:chunk_ids ["c1"]}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (true? @retrieve-called)
              "Must fetch from Typesense even for previously-seen chunk IDs")
          (is (re-find #"Full content from Typesense" result))
          ;; Workspace chunk must have content_markdown
          (is (= "Full content from Typesense"
                 (:content_markdown (get (:chunks @!ws) "c1")))))))))

;; =============================================================================
;; Behavioral: search metadata-only, invalid chunk_range, invalid max_content_length
;; =============================================================================

(deftest test-search-passes-metadata-only-to-retrieval
  (testing "search tool passes :metadata-only true to retrieval sub-skill"
    (let [!ws (workspace/create-workspace)
          captured-params (atom nil)]
      (with-redefs [tools/execute-sub-skill
                    (fn [_skill-id _inputs _opts & [params]]
                      (reset! captured-params params)
                      {:outputs {:chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                                           :content_length 100}]
                                 :search-attribution {}}})]
        (tools/execute-tool-call
          "search"
          {:queries ["test query"]}
          !ws
          {:docs-collection "docs"
           :chunks-collection "chunks"
           :phrases-collection "phrases"
           :conversation-history []
           :opts {:tenant "t" :environment "e"}})
        (is (true? (:metadata-only @captured-params))
            "search must request metadata-only retrieval")
        (is (= 1 (count (:search-history @!ws))))
        (is (= ["test query"] (-> @!ws :search-history first :queries)))))))

(deftest test-rerank-guides-agent-to-read-after-search
  (testing "rerank_results refuses to rerank metadata-only search results and suggests read_chunks"
    (let [!ws (workspace/create-workspace)]
      (workspace/record-search!
       !ws
       {:queries ["årsverk Digdir 2022"]
        :chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 2 :content_length 100
                  "docs" {:total_chunks 5}}
                 {:chunk_id "c2" :doc_num "d1" :chunk_index 3 :content_length 80
                  "docs" {:total_chunks 5}}]
        :new-count 2
        :attribution {}
        :fallback? false})
        (let [result (tools/execute-tool-call
                     "rerank_results"
                     {:query "årsverk Digdir 2022"}
                     !ws
                     {:docs-collection "docs"
                      :chunks-collection "chunks"
                      :phrases-collection "phrases"
                      :conversation-history []
                      :opts {:tenant "t" :environment "e"}})]
        (is (re-find #"Use read_chunks before trying to rerank" result))
        (is (str/includes? result "\"chunk_ids\": [\"c1\",\"c2\"]"))
        (is (str/includes? result "\"doc_num\":\"d1\""))))))

(deftest test-rerank-distinguishes-empty-after-reads-from-never-read
  (testing "rerank_results emits a distinct message when reads were attempted but the workspace is empty"
    (let [!ws (workspace/create-workspace)]
      (workspace/record-search!
       !ws {:queries ["q"] :chunks [] :new-count 0 :attribution {} :fallback? false})
      ;; Simulate that read_chunks was called and recorded — chunks may have been
      ;; suppressed before storage, so :chunks stays empty.
      (workspace/record-read!
       !ws {:chunk-ids ["c1"]} [])
      (let [result (tools/execute-tool-call
                    "rerank_results"
                    {:query "q"}
                    !ws
                    {:docs-collection "docs"
                     :chunks-collection "chunks"
                     :phrases-collection "phrases"
                     :conversation-history []
                     :opts {:tenant "t" :environment "e"}})]
        (is (re-find #"You called read_chunks 1 time" result)
            "Message must reflect that reads were attempted")
        (is (re-find #"Likely causes:" result))
        (is (re-find #"Do NOT repeat the same read" result))
        (is (not (re-find #"Use read_chunks before trying to rerank" result))
            "Must not show the never-read message when reads have happened")))))

(deftest test-rerank-shows-budget-state-when-reads-empty
  (testing "When all read budget is exhausted, the budget-exhausted message wins over the empty-after-reads message"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc :budget-limits {:max-search-passes 4
                                        :max-read-operations 2
                                        :max-read-content-length 1000})
      (workspace/record-search!
       !ws {:queries ["q"] :chunks [] :new-count 0 :attribution {} :fallback? false})
      (workspace/record-read! !ws {:chunk-ids ["c1"]} [])
      (workspace/record-read! !ws {:chunk-ids ["c2"]} [])
      (let [result (tools/execute-tool-call
                    "rerank_results"
                    {:query "q"}
                    !ws
                    {:docs-collection "docs"
                     :chunks-collection "chunks"
                     :phrases-collection "phrases"
                     :conversation-history []
                     :opts {:tenant "t" :environment "e"}})]
        (is (re-find #"read budget is exhausted" result))
        (is (not (re-find #"You called read_chunks 2 times" result))
            "Budget-exhausted message takes precedence so the LLM stops trying to read")))))

(deftest test-generate-guides-agent-to-read-after-search
  (testing "generate_response does not call synthesis when only metadata has been searched"
    (let [!ws (workspace/create-workspace)
          synthesis-called? (atom false)]
      (workspace/record-search!
       !ws
       {:queries ["årsverk Digdir 2022"]
        :chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 0 :content_length 100
                  "docs" {:total_chunks 2}}]
        :new-count 1
        :attribution {}
        :fallback? false})
      (with-redefs [tools/execute-sub-skill
                    (fn [& _]
                      (reset! synthesis-called? true)
                      {:outputs {:response "should not be called"}})]
        (let [result (tools/execute-tool-call
                       "generate_response"
                       {:query "årsverk Digdir 2022"}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (re-find #"Use read_chunks before trying to generate a response" result))
          (is (false? @synthesis-called?)))))))

(deftest test-read-chunks-records-budget-and-history
  (testing "read_chunks records read history and cumulative content length"
    (let [!ws (workspace/create-workspace)]
      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [& _]
                      [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                        :content_markdown "abcdef" :content_length 6}
                       {:chunk_id "c2" :doc_num "d1" :chunk_index 1
                        :content_markdown "ghi" :content_length 3}])]
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:chunk_ids ["c1" "c2"]}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (re-find #"9 chars this call, 9 cumulative" result))
          (is (= 1 (count (:read-history @!ws))))
          (is (= 9 (:read-content-length @!ws)))
          (is (= [:chunk-ids] (map :mode (:read-history @!ws))))
          (is (nil? (:max-content-length (first (:read-history @!ws))))
              "Full read should not have max-content-length"))))))

(deftest test-read-chunks-records-skim-vs-full-read
  (testing "read_chunks with max_content_length records skim indicator in read history"
    (let [!ws (workspace/create-workspace)]
      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [& _]
                      [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                        :content_markdown "abc" :content_length 3}])]
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:chunk_ids ["c1"] :max_content_length 200}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (string? result))
          (is (= 1 (count (:read-history @!ws))))
          (is (= 200 (:max-content-length (first (:read-history @!ws)))))))))

  (testing "read_chunks without max_content_length omits skim indicator"
    (let [!ws (workspace/create-workspace)]
      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [& _]
                      [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                        :content_markdown "abc" :content_length 3}])]
        (tools/execute-tool-call
          "read_chunks"
          {:chunk_ids ["c1"]}
          !ws
          {:docs-collection "docs"
           :chunks-collection "chunks"
           :phrases-collection "phrases"
           :conversation-history []
           :opts {:tenant "t" :environment "e"}})
        (is (nil? (:max-content-length (first (:read-history @!ws)))))))))

(deftest test-format-trace-file-includes-skim-indicator
  (testing "Trace output shows skim=true for reads with max-content-length"
    (let [trace-text (#'core/format-trace-file
                      {:query "q"
                       :model "m"
                       :trace []
                       :status :success
                       :response ""
                       :read-history [{:mode :chunk-ids
                                       :chunk-ids ["c1"]
                                       :returned-count 1
                                       :content-length 200
                                       :max-content-length 500}
                                      {:mode :chunk-ids
                                       :chunk-ids ["c2"]
                                       :returned-count 1
                                       :content-length 3000}]})]
      (is (re-find #"skim=true" trace-text))
      (is (re-find #"max-content-length=500" trace-text))
      (is (= 1 (count (re-seq #"skim=true" trace-text)))
          "Only the skim entry should have the indicator"))))

(deftest test-read-chunks-hard-limits-multi-chunk-reads-by-remaining-budget
  (testing "read_chunks keeps multi-chunk reads within the remaining read-char budget"
    (let [!ws (workspace/create-workspace)]
      (swap! !ws assoc :budget-limits {:max-search-passes 4
                                       :max-read-operations 6
                                       :max-read-content-length 10})
      (workspace/record-search!
       !ws
       {:queries ["test query"]
        :chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 0 :content_length 6
                  "docs" {:total_chunks 2}}
                 {:chunk_id "c2" :doc_num "d1" :chunk_index 1 :content_length 6
                  "docs" {:total_chunks 2}}]
        :new-count 2
        :attribution {}
        :fallback? false})
      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [& _]
                      [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                        :content_markdown "abcdef" :content_length 6}])]
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:chunk_ids ["c1" "c2"]}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (re-find #"Read 1 chunks" result))
          (is (re-find #"Budget limited this read" result))
          (is (re-find #"Skipped 1 before fetch" result))
          (is (= 1 (count (:read-history @!ws))))
          (is (= 6 (:read-content-length @!ws)))
          (is (= ["c1"] (-> @!ws :read-history first :returned-chunk-ids))))))))

(deftest test-range-read-too-small-is-rejected-before-fetch
  (testing "Range reads that cannot fit the remaining content budget do not hit storage"
    (let [!ws (workspace/create-workspace)
          fetch-called? (atom false)]
      (swap! !ws assoc :budget-limits {:max-search-passes 4
                                       :max-read-operations 6
                                       :max-read-content-length 30})
      (workspace/record-search!
       !ws
       {:queries ["test query"]
        :chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 0 :content_length 40
                  "docs" {:total_chunks 5}}
                 {:chunk_id "c2" :doc_num "d1" :chunk_index 1 :content_length 40
                  "docs" {:total_chunks 5}}
                 {:chunk_id "c3" :doc_num "d1" :chunk_index 2 :content_length 40
                  :docs {:total_chunks 5}}]
        :new-count 3
        :attribution {}
        :fallback? false})
      (with-redefs [rag/retrieve-chunks-by-range
                    (fn [& _]
                      (reset! fetch-called? true)
                      [])]
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:doc_num "d1" :chunk_range {:from 0 :to 2}}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (re-find #"Remaining budget \(30/30 chars\) is too small" result))
          (is (false? @fetch-called?))
          (is (empty? (:read-history @!ws))))))))

(deftest test-read-chunks-invalid-chunk-range-values
  (testing "read_chunks handles negative and non-numeric chunk_range values safely"
    (let [!ws (workspace/create-workspace)
          range-args (atom nil)]
      (with-redefs [rag/retrieve-chunks-by-range
                    (fn [_docs _chunks doc-num from to _opts]
                      (reset! range-args {:from from :to to})
                      [{:chunk_id "r1" :doc_num doc-num :chunk_index from
                        :content_markdown "Content"}])]
        ;; Negative from/to should be clamped to 0
        (tools/execute-tool-call
          "read_chunks"
          {:doc_num "d1" :chunk_range {:from -5 :to -1}}
          !ws
          {:docs-collection "docs"
           :chunks-collection "chunks"
           :phrases-collection "phrases"
           :conversation-history []
           :opts {:tenant "t" :environment "e"}})
        (is (>= (:from @range-args) 0) "from must be clamped to >= 0")
        (is (>= (:to @range-args) 0) "to must be clamped to >= 0"))))
  (testing "read_chunks returns error string for non-numeric chunk_range values"
    (let [!ws (workspace/create-workspace)
          result (tools/execute-tool-call
                   "read_chunks"
                   {:doc_num "d1" :chunk_range {:from "abc" :to "xyz"}}
                   !ws
                   {:docs-collection "docs"
                    :chunks-collection "chunks"
                    :phrases-collection "phrases"
                    :conversation-history []
                    :opts {:tenant "t" :environment "e"}})]
      (is (string? result))
      (is (re-find #"(?i)error" result)
          "Non-numeric chunk_range values should produce an error message"))))

(deftest test-read-chunks-invalid-max-content-length-ignored
  (testing "read_chunks ignores invalid max_content_length (negative, zero, string)"
    (let [!ws (workspace/create-workspace)
          mock-chunks [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                        :content_markdown "Full content here" :content_length 17}]]
      (with-redefs [rag/retrieve-chunks-by-id (tu/recording-fn mock-chunks)]
        ;; Negative value — should be treated as nil (no truncation)
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:chunk_ids ["c1"] :max_content_length -10}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (not (re-find #"\[truncated\]" result))
              "Negative max_content_length should not truncate"))
        ;; Zero value — should be treated as nil (no truncation)
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:chunk_ids ["c1"] :max_content_length 0}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (not (re-find #"\[truncated\]" result))
              "Zero max_content_length should not truncate"))
        ;; String value — should be treated as nil (no truncation)
        (let [result (tools/execute-tool-call
                       "read_chunks"
                       {:chunk_ids ["c1"] :max_content_length "abc"}
                       !ws
                       {:docs-collection "docs"
                        :chunks-collection "chunks"
                        :phrases-collection "phrases"
                        :conversation-history []
                        :opts {:tenant "t" :environment "e"}})]
          (is (not (re-find #"\[truncated\]" result))
              "String max_content_length should not truncate"))))))

;; =============================================================================
;; Rerank Empty Workspace Test
;; =============================================================================

(deftest test-format-rerank-result-empty-workspace
  (testing "Returns hint when reranked list is empty"
    (let [result {:outputs {:chunks []}}
          formatted (tools/format-rerank-result result)]
      (is (re-find #"No chunks in workspace to rerank" formatted))
      (is (re-find #"read_chunks" formatted)))))

;; =============================================================================
;; f85c984 recall-tuning knobs: rescore-with-title-bonus, enforce-strategy-quota,
;; search-display-limit, auto-read-top-k
;; =============================================================================

(deftest test-rescore-with-title-bonus-promotes-title-matching-chunk
  (testing "When weight > 0, equal rerank-score with stronger title overlap ranks higher"
    (let [;; Both chunks have the same rerank-score; one has a doc-ref sub-map
          ;; whose title matches the query, the other doesn't.
          ;; title-overlap-bonus scans chunk's *values* for a map with :title/:url.
          chunks [{:chunk_id "no-match"
                   :rerank-score 0.5
                   :doc {:title "Random unrelated content here"}}
                  {:chunk_id "title-match"
                   :rerank-score 0.5
                   :doc {:title "Dialogporten API specification"}}]
          result (tools/rescore-with-title-bonus chunks "dialogporten api" 5.0)
          ids (mapv :chunk_id result)]
      (is (= ["title-match" "no-match"] ids)
          "Title-matching chunk floats above equal-rerank-score peer")))

  (testing "Weight 0 returns chunks unchanged"
    (let [chunks [{:chunk_id "a" :rerank-score 0.3 :doc {:title "Foo"}}
                  {:chunk_id "b" :rerank-score 0.7 :doc {:title "Bar"}}]
          result (tools/rescore-with-title-bonus chunks "anything" 0)]
      (is (= chunks result) "weight=0 short-circuits"))))

(deftest test-enforce-strategy-quota-guarantees-per-strategy-slots
  (testing "quota=2 puts top-2 of each strategy at head before the rerank tail"
    (let [chunks [;; Top 10 by current rerank; mixed strategy attribution.
                  {:chunk_id "c1" :search-types #{:phrase}}
                  {:chunk_id "c2" :search-types #{:phrase}}
                  {:chunk_id "c3" :search-types #{:phrase}}
                  {:chunk_id "c4" :search-types #{:metadata}}
                  {:chunk_id "c5" :search-types #{:metadata}}
                  {:chunk_id "c6" :search-types #{:metadata}}
                  {:chunk_id "c7" :search-types #{:content}}
                  {:chunk_id "c8" :search-types #{:content}}
                  {:chunk_id "c9" :search-types #{:content}}]
          result (#'tools/enforce-strategy-quota chunks 2)
          head (vec (take 6 (map :chunk_id result)))]
      ;; Round-robin interleave: phrase[0], metadata[0], content[0],
      ;; phrase[1], metadata[1], content[1]
      (is (= ["c1" "c4" "c7" "c2" "c5" "c8"] head)
          "First 6 are top-2 of each strategy interleaved")
      (is (= 9 (count result)) "No chunk dropped")))

  (testing "quota=0 returns chunks unchanged"
    (let [chunks [{:chunk_id "a" :search-types #{:phrase}}
                  {:chunk_id "b" :search-types #{:metadata}}]]
      (is (= chunks (#'tools/enforce-strategy-quota chunks 0))))))

(deftest test-agent-search-display-limit-reads-skill-params
  (testing "When :search-display-limit is set, helper returns that value (clamped 1..100)"
    (let [ctx-with-50 {:opts {:skill-params {:builtin/agent {:search-display-limit 50}}}}
          ctx-with-default {:opts {:skill-params {}}}
          ctx-with-over {:opts {:skill-params {:builtin/agent {:search-display-limit 999}}}}]
      (is (= 50 (#'tools/agent-search-display-limit ctx-with-50)))
      (is (pos? (#'tools/agent-search-display-limit ctx-with-default))
          "Absent → falls back to a positive default")
      (is (= 100 (#'tools/agent-search-display-limit ctx-with-over))
          "Clamped to 100"))))

(deftest test-format-search-metadata-results-respects-display-limit
  (testing "display-limit caps the number of chunk rows in the formatted output"
    (let [chunks (mapv (fn [i] {:chunk_id (str "c" i)
                                :doc_num (str "d" i)
                                :title "T"
                                :rerank-score 1.0})
                       (range 50))
          formatted (tools/format-search-metadata-results chunks 50 0 1 {} 5)
          ;; Rows render as "N. chunk_id=cX doc_num=dX ..." starting at "1."
          chunk-lines (count (re-seq #"(?m)^\d+\. chunk_id=" formatted))]
      (is (= 5 chunk-lines)
          "Exactly 5 chunk rows rendered when display-limit=5"))))

(deftest test-auto-read-top-k-noop-when-k-zero
  (testing "k=0 → no execute-sub-skill call, no workspace mutation, returns []"
    (let [!workspace (workspace/create-workspace)
          stub-called? (atom false)]
      (with-redefs [tools/execute-sub-skill
                    (fn [& _] (reset! stub-called? true) {:outputs {:chunks []}})]
        (let [ids (#'tools/auto-read-top-k!
                    !workspace
                    {:docs-collection "d" :chunks-collection "c" :opts {}}
                    [{:chunk_id "x"} {:chunk_id "y"}]
                    0)]
          (is (= [] ids))
          (is (not @stub-called?) "k=0 → no sub-skill invocation"))))))
