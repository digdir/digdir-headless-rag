(ns digdir.skills.invoke-test
  "Tests for the canonical invoke-rag entry point."
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.skills.api :as skills-api]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.skills.invoke :as invoke]))

(defn- with-stub-graph
  "Run f with skills-api/run-skill-graph stubbed to return `result`. Captures
   (skill-graph-id, inputs, opts) in `captured`."
  [result captured f]
  (with-redefs [skills-api/run-skill-graph
                (fn [skill-graph-id inputs opts]
                  (reset! captured {:skill-graph-id skill-graph-id
                                    :inputs inputs
                                    :opts opts})
                  result)]
    (f)))

(deftest invoke-rag-success-shape
  (testing "Normalizes outputs to canonical return shape on success"
    (let [captured (atom nil)
          stub-result {:outputs {:response "Hello world."
                                 :chunks [{:chunk_id "c1"}]
                                 :queries ["q1"]
                                 :search-attribution {:phrase 1 :metadata 0 :content 0 :merged 1}}
                      :step-results {}
                      :execution-metadata {:total-duration-ms 42}}]
      (with-stub-graph stub-result captured
        (fn []
          (let [result (invoke/invoke-rag
                         {:user-query "What is X?"
                          :skill-graph-id :builtin/agent-rag-graph-bundled
                          :execution-scope {:tenant "t" :dataset-config-key "d" :agent-id "a"}
                          :collections {:docs-collection "docs"
                                        :chunks-collection "chunks"
                                        :phrases-collection "phrases"}})]
            (is (= :complete (:status result)))
            (is (= "Hello world." (:response result)))
            (is (false? (:insufficient? result)))
            (is (nil? (:clarification result)))
            (is (= [{:chunk_id "c1"}] (:chunks result)))
            (is (= ["q1"] (:queries result)))
            (is (nil? (:error result)))
            (is (= stub-result (:raw-result result))))
          (let [{:keys [skill-graph-id inputs opts]} @captured]
            (is (= :builtin/agent-rag-graph-bundled skill-graph-id))
            (is (= "What is X?" (:user-query inputs)))
            (is (= "What is X?" (:claim inputs)))
            (is (= "docs" (:docs-collection inputs)))
            (is (= "t" (:tenant opts)))
            (is (= "a" (:agent-id opts)))))))))

(deftest invoke-rag-response-fallback-uses-report
  (testing ":report fallback fires when :response/:verification are absent"
    (let [captured (atom nil)
          stub-result {:outputs {:report "## Findings\n- ..."}
                       :step-results {}
                       :execution-metadata {}}]
      (with-stub-graph stub-result captured
        (fn []
          (let [result (invoke/invoke-rag
                         {:user-query "Audit something."
                          :skill-graph-id :docs/self-improve-graph
                          :execution-scope {:tenant "t" :dataset-config-key "d"}})]
            (is (= "## Findings\n- ..." (:response result)))
            (is (= :complete (:status result)))))))))

(deftest invoke-rag-response-fallback-counts-chunks
  (testing "Chunks-only outputs render the retrieval summary"
    (let [captured (atom nil)
          stub-result {:outputs {:chunks [{:chunk_id "a"} {:chunk_id "b"}]}
                       :step-results {}
                       :execution-metadata {}}]
      (with-stub-graph stub-result captured
        (fn []
          (let [result (invoke/invoke-rag
                         {:user-query "Top docs?"
                          :skill-graph-id :builtin/agent-rag-graph-bundled
                          :execution-scope {:tenant "t" :dataset-config-key "d"}})]
            (is (= "Retrieved and reranked 2 chunks." (:response result)))))))))

(deftest invoke-rag-needs-clarification
  (testing "Clarification request flips :status to :needs-clarification"
    (let [captured (atom nil)
          stub-result {:outputs {:response "Need more context."
                                 :clarification-request
                                 {:question "Which year?"
                                  :options ["2024" "2025"]
                                  :context-summary "Multi-year query."}}
                       :step-results {}
                       :execution-metadata {}}]
      (with-stub-graph stub-result captured
        (fn []
          (let [result (invoke/invoke-rag
                         {:user-query "How many?"
                          :skill-graph-id :builtin/agent-rag-graph-bundled
                          :execution-scope {:tenant "t" :dataset-config-key "d"}})]
            (is (= :needs-clarification (:status result)))
            (is (= {:question "Which year?"
                    :options ["2024" "2025"]
                    :context-summary "Multi-year query."}
                   (:clarification result)))))))))

(deftest invoke-rag-error-result
  (testing "Skills-layer errors are surfaced as :status :error"
    (let [captured (atom nil)
          stub-result {:error {:error-type :missing-input
                               :error-message "User query was blank"}}]
      (with-stub-graph stub-result captured
        (fn []
          (let [result (invoke/invoke-rag
                         {:user-query " "
                          :skill-graph-id :builtin/agent-rag-graph-bundled
                          :execution-scope {:tenant "t" :dataset-config-key "d"}})]
            (is (= :error (:status result)))
            (is (= {:error-type :missing-input
                    :error-message "User query was blank"}
                   (:error result)))
            (is (= "" (:response result)))))))))

(deftest invoke-rag-exception-normalization
  (testing "Thrown exceptions are normalized into the error envelope"
    (with-redefs [skills-api/run-skill-graph
                  (fn [_ _ _]
                    (throw (ex-info "boom" {:cause :test})))]
      (let [result (invoke/invoke-rag
                     {:user-query "X"
                      :skill-graph-id :builtin/agent-rag-graph-bundled
                      :execution-scope {:tenant "t" :dataset-config-key "d"}})]
        (is (= :error (:status result)))
        (is (= :exception (get-in result [:error :error-type])))
        (is (= "boom" (get-in result [:error :error-message])))))))

(deftest invoke-rag-agent-terminal-error-is-an-error
  (testing "An agent that terminated in its own :error state is :status :error (#303)"
    ;; These outputs are the ones a real fresh-install run produces, copied
    ;; from a live tools/call: the agent's first LLM call cannot decrypt its
    ;; provider key (#279), the loop finalizes with :terminal-state :error,
    ;; and the failure text lands in :response. Reported as :complete, it
    ;; reached MCP as `isError: false` — a dead call rendered as a good
    ;; answer, which is what anything branching on isError (or counting
    ;; successes) would have believed.
    (let [captured (atom nil)
          stub-result {:outputs {:response "LLM request failed at iteration 0: Tag mismatch"
                                 :terminal-state :error}
                       :step-results {}
                       :execution-metadata {}}]
      (with-stub-graph stub-result captured
        (fn []
          (let [result (invoke/invoke-rag
                         {:user-query "Hva er Digdir?"
                          :skill-graph-id :builtin/agent-rag-graph-bundled
                          :execution-scope {:tenant "t" :dataset-config-key "d"}})]
            (is (= :error (:status result)))
            (is (= :agent-terminal-error (get-in result [:error :error-type])))
            (is (= "LLM request failed at iteration 0: Tag mismatch"
                   (get-in result [:error :error-message])))
            ;; The text stays in :response on purpose: MCP renders it as the
            ;; result's content beside isError:true, so the caller gets a
            ;; message it can act on rather than an empty error.
            (is (= "LLM request failed at iteration 0: Tag mismatch"
                   (:response result)))))))))

(deftest invoke-rag-other-terminal-states-stay-complete
  (testing "Only :terminal-state :error flips the status — a finalized or
            clarifying agent still answered (#303)"
    (doseq [terminal [:finalize :response nil]]
      (let [captured (atom nil)
            stub-result {:outputs {:response "A real answer."
                                   :terminal-state terminal}
                         :step-results {}
                         :execution-metadata {}}]
        (with-stub-graph stub-result captured
          (fn []
            (let [result (invoke/invoke-rag
                           {:user-query "Hva er Digdir?"
                            :skill-graph-id :builtin/agent-rag-graph-bundled
                            :execution-scope {:tenant "t" :dataset-config-key "d"}})
                  ctx (str "terminal-state " (pr-str terminal))]
              (is (= :complete (:status result)) ctx)
              (is (nil? (:error result)) ctx)
              (is (= "A real answer." (:response result)) ctx))))))))

(deftest invoke-rag-passes-progress-fn-through
  (testing "Progress-fn lands on opts so the skills layer can stream events"
    (let [captured (atom nil)
          progress-fn (fn [_] nil)
          stub-result {:outputs {:response "ok"} :step-results {} :execution-metadata {}}]
      (with-stub-graph stub-result captured
        (fn []
          (invoke/invoke-rag
            {:user-query "X"
             :skill-graph-id :builtin/agent-rag-graph-bundled
             :execution-scope {:tenant "t" :dataset-config-key "d"}
             :progress-fn progress-fn})
          (is (= progress-fn (get-in @captured [:opts :progress-fn]))))))))

(deftest invoke-rag-validates-required-inputs
  (testing "Missing :user-query throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (invoke/invoke-rag
                   {:skill-graph-id :builtin/agent-rag-graph-bundled
                    :execution-scope {:tenant "t" :dataset-config-key "d"}}))))
  (testing "Non-keyword :skill-graph-id throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (invoke/invoke-rag
                   {:user-query "X"
                    :skill-graph-id "builtin/agent-rag-graph-bundled"
                    :execution-scope {:tenant "t" :dataset-config-key "d"}})))))

(def ^:private realistic-workspace
  "The INTERNAL workspace, in the shape the agent actually builds it.

   Two things here are the whole point of this fixture and neither is
   incidental (#460):

   1. `:chunks` is a MAP keyed by chunk_id, because `add-chunks-to-workspace`
      zipmaps by `:chunk_id` to dedupe. The previous fixture used a VECTOR — a
      shape the producer cannot produce — which is why a consumer reading the
      workspace looked correct here and published `[{} {}]` in production.
   2. The document join sits under a COLLECTION-NAMED key, because that is what
      `$<docs-collection>(url,title,…)` returns. `:title` and `:url` are NOT at
      the top level until something lifts them."
  {:chunks {"c1" {:chunk_id "c1" :doc_num "7" :chunk_index 0 :retrieval-prior 0.9
                  :website_documents_ab897fbdedfa {:title "Årsrapport"
                                                   :url "https://example.test/aar"
                                                   :total_chunks 12}}
            "c2" {:chunk_id "c2" :doc_num "9" :chunk_index 1 :retrieval-prior 0.5
                  :website_documents_ab897fbdedfa {:title "Tildelingsbrev"
                                                   :total_chunks 4}}}
   :citations [{:index 1 :chunk-id "c1"}]
   :citation-index {1 "c1"}})

(defn- agent-graph-outputs
  "What the outer agent graph emits after #460: the PUBLISHED `:chunks` vector
   alongside the internal `:workspace-final`.

   `:chunks` is derived by calling the producer's own function rather than by
   hand-writing a vector, so this stub cannot express a shape the producer
   cannot produce — which is the defect the old fixture had."
  [extra]
  (merge {:chunks (workspace/chunks-for-output realistic-workspace)
          :workspace-final realistic-workspace}
         extra))

(deftest invoke-rag-surfaces-agent-graph-evidence
  ;; Evidence retrieved by an agent run must reach the caller of the canonical
  ;; entry point (MCP, sweep runner, playground). It has failed that twice, in
  ;; opposite directions:
  ;;
  ;;   1. Reading only `(:chunks outputs)` when the graph declared no `:chunks`
  ;;      handed every caller `[]` for a run that had actually retrieved and
  ;;      cited documents.
  ;;   2. Falling back to `outputs → workspace-final → :chunks` fixed that and
  ;;      published `[{} {}]` instead, because the workspace's `:chunks` is a
  ;;      MAP and `mapv select-keys` over a map iterates `MapEntry` (#460).
  ;;
  ;; A COUNT CANNOT TELL THOSE APART FROM A CORRECT RESULT: `[{} {}]` and two
  ;; populated chunks both count 2. So every assertion below is on a key set or
  ;; a populated field. Worst on `:needs-clarification`, where the visible
  ;; result is a one-line question and the evidence gathered on the way to
  ;; asking it is all there is — so both paths are pinned.
  (letfn [(published-chunks [r] (:chunks r))]

    (testing "a completed agent run surfaces the retrieved evidence"
      (let [captured (atom nil)
            stub-result {:outputs (agent-graph-outputs {:response "Altinn 3 er en plattform."})
                         :step-results {} :execution-metadata {}}]
        (with-stub-graph stub-result captured
          (fn []
            (let [r (invoke/invoke-rag {:user-query "hva er Altinn 3?"
                                        :skill-graph-id :builtin/agent-rag-graph-bundled
                                        :execution-scope {:tenant "t" :dataset-config-key "d"}})
                  chunks (published-chunks r)]
              (is (every? seq chunks)
                  "every published chunk must carry keys — [{} {}] is the map-shaped defect")
              (is (= ["c1" "c2"] (mapv :chunk_id chunks))
                  "chunk ids must survive; a MapEntry yields nil here")
              (is (= ["Årsrapport" "Tildelingsbrev"] (mapv :title chunks))
                  "the joined document's title must be lifted to the top level")
              (is (= "https://example.test/aar" (:url (first chunks)))
                  "the joined document's url must be lifted to the top level")
              (is (= 1 (count (get-in r [:diagnostics :citations])))
                  "citations must reach diagnostics, which is where the UI reads them"))))))

    (testing "a clarification carries the evidence gathered before asking"
      (let [captured (atom nil)
            stub-result {:outputs (agent-graph-outputs
                                    {:response "Which product?"
                                     :clarification-request {:question "Which product?"
                                                             :options ["Events" "Broker"]
                                                             :context-summary "ambiguous"}})
                         :step-results {} :execution-metadata {}}]
        (with-stub-graph stub-result captured
          (fn []
            (let [r (invoke/invoke-rag {:user-query "How do I set up an events webhook?"
                                        :skill-graph-id :builtin/agent-rag-graph-bundled
                                        :execution-scope {:tenant "t" :dataset-config-key "d"}})
                  chunks (published-chunks r)]
              (is (= :needs-clarification (:status r)))
              (is (every? seq chunks)
                  "asking a question must not throw away what was already retrieved")
              (is (= ["c1" "c2"] (mapv :chunk_id chunks))
                  "and what survives must be identifiable, not empty objects"))))))))
