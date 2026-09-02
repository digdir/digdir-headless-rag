(ns digdir.skills.builtin.overview-test
  "Tests for the AI-overview graph (#240).

   Two layers, deliberately:

   1. The decline rule as pure functions (`evaluate-evidence`,
      `finalize-overview`). This is where the product decision lives, so it is
      tested directly rather than inferred from a graph run.

   2. The REAL graph, run through the REAL graph runner, with only the three
      network-bound skills stubbed (query-planner, retrieval, rerank) plus the
      one LLM-bound one (overview-synthesis). A graph that is correct in source
      and wrong when executed is the failure mode this whole issue is about, so
      the wiring is executed rather than read: that the `:condition` really
      skips synthesis, that a declined run really carries no `:response`, and
      that no LLM call is made on the decline path."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.builtin.overview :as overview]
            [digdir.skills.context :as ctx]
            [digdir.skills.graph.runner :as runner]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- mk-chunk
  ([id score] (mk-chunk id score (str "doc-" id)))
  ([id score doc] {:chunk_id id :rerank-score score :doc_num doc
                   :content_markdown (str "content " id)}))

(defn- context-doc [id]
  {:page_content (str "content " id) :metadata {:source id}})

(def ^:private thick-evidence
  {:chunks [(mk-chunk "c1" 0.90 "doc-a")
            (mk-chunk "c2" 0.80 "doc-b")
            (mk-chunk "c3" 0.75 "doc-c")]
   :context-docs [(context-doc "c1") (context-doc "c2") (context-doc "c3")]})

;; =============================================================================
;; 1. The decline rule — pre-synthesis
;; =============================================================================

(deftest evaluate-evidence-passes-on-thick-evidence
  (testing "three corroborating chunks across three documents clears the floor"
    (let [{:keys [sufficient? reason]} (overview/evaluate-evidence thick-evidence {})]
      (is (true? sufficient?))
      (is (nil? reason)))))

(deftest evaluate-evidence-declines-with-no-evidence
  (testing "no context docs at all"
    (let [{:keys [sufficient? reason]}
          (overview/evaluate-evidence {:chunks [] :context-docs []} {})]
      (is (false? sufficient?))
      (is (= :no-evidence reason)))))

(deftest evaluate-evidence-declines-on-single-source
  (testing "one context doc is an assertion, not a synthesis"
    (let [{:keys [sufficient? reason]}
          (overview/evaluate-evidence {:chunks [(mk-chunk "c1" 0.9)]
                                       :context-docs [(context-doc "c1")]}
                                      {})]
      (is (false? sufficient?))
      (is (= :too-few-sources reason)))))

(deftest evaluate-evidence-declines-when-uncorroborated
  (testing "one strong hit above a flat field is a single passage, not a consensus"
    (let [{:keys [sufficient? reason summary]}
          (overview/evaluate-evidence {:chunks [(mk-chunk "c1" 0.90 "doc-a")
                                                (mk-chunk "c2" 0.10 "doc-b")
                                                (mk-chunk "c3" 0.05 "doc-c")]
                                       :context-docs [(context-doc "c1")
                                                      (context-doc "c2")
                                                      (context-doc "c3")]}
                                      {})]
      (is (false? sufficient?))
      (is (= :uncorroborated reason))
      (is (= 1 (:supporting-chunk-count summary))))))

(deftest evaluate-evidence-honours-absolute-floor-when-set
  (testing ":min-top-score is off by default and enforced when supplied"
    (is (true? (:sufficient? (overview/evaluate-evidence thick-evidence {}))))
    (is (= :below-score-floor
           (:reason (overview/evaluate-evidence thick-evidence {:min-top-score 0.95}))))))

(deftest evaluate-evidence-skips-score-gates-when-rerank-disabled
  (testing "chunks with no :rerank-score must not be read as bad scores"
    ;; :builtin/rerank {:enabled false} produces chunks with no score. Treating
    ;; missing as zero would decline every overview on such a dataset.
    (let [unscored [{:chunk_id "c1" :doc_num "doc-a"}
                    {:chunk_id "c2" :doc_num "doc-b"}]
          {:keys [sufficient? reason summary]}
          (overview/evaluate-evidence {:chunks unscored
                                       :context-docs [(context-doc "c1")
                                                      (context-doc "c2")]}
                                      {})]
      (is (true? sufficient?))
      (is (nil? reason))
      (is (false? (:scored? summary))))))

(deftest evaluate-evidence-single-document-gate-is-opt-in
  (testing "two chunks from one document pass by default, decline when required"
    (let [one-doc {:chunks [(mk-chunk "c1" 0.9 "doc-a") (mk-chunk "c2" 0.85 "doc-a")]
                   :context-docs [(context-doc "c1") (context-doc "c2")]}]
      (is (true? (:sufficient? (overview/evaluate-evidence one-doc {}))))
      (is (= :single-document
             (:reason (overview/evaluate-evidence one-doc {:min-distinct-documents 2})))))))

;; =============================================================================
;; 2. The decline rule — post-synthesis
;; =============================================================================

(def ^:private good-synthesis
  {:evidence-sufficient? true
   :overview-response "Altinn Autorisasjon styrer tilgang [1]. Det gjelder for alle tjenester [2]."
   :citations [{:index 1 :chunk-id "c1"} {:index 2 :chunk-id "c2"}]
   :citation-validation {:all-valid? true}
   :insufficient-context false})

(deftest finalize-keeps-a-well-cited-overview
  (let [out (overview/finalize-overview good-synthesis)]
    (is (false? (:overview-declined? out)))
    (is (= (:overview-response good-synthesis) (:response out)))
    (is (= 2 (count (:citations out))))))

(deftest finalize-reports-the-pre-synthesis-decline
  (testing "a gate decline is passed through with its reason, not relabelled"
    (let [out (overview/finalize-overview {:evidence-sufficient? false
                                           :evidence-decline-reason :uncorroborated})]
      (is (true? (:overview-declined? out)))
      (is (= :uncorroborated (:overview-decline-reason out)))
      (is (= "" (:response out))))))

(deftest finalize-suppresses-an-uncited-overview
  (testing "an answer a reader cannot check in one hop is worth less than none"
    (let [out (overview/finalize-overview (assoc good-synthesis :citations []))]
      (is (true? (:overview-declined? out)))
      (is (= :uncited (:overview-decline-reason out)))
      (is (= "" (:response out))))))

(deftest finalize-suppresses-invalid-citations
  (let [out (overview/finalize-overview
              (assoc good-synthesis :citation-validation {:all-valid? false}))]
    (is (true? (:overview-declined? out)))
    (is (= :invalid-citations (:overview-decline-reason out)))))

(deftest finalize-honours-the-sentinel
  (testing "the model's own decline is exact and language-independent"
    (doseq [response ["INSUFFICIENT" "  INSUFFICIENT. " "insufficient"]]
      (let [out (overview/finalize-overview
                  (assoc good-synthesis :overview-response response :citations []))]
        (is (true? (:overview-declined? out)) (str "for response: " (pr-str response)))
        (is (= :model-declined (:overview-decline-reason out)))))))

(deftest finalize-honours-the-regex-detector-as-a-second-net
  (testing "synthesis's own insufficiency flag also suppresses"
    (let [out (overview/finalize-overview
                (assoc good-synthesis :insufficient-context true))]
      (is (true? (:overview-declined? out)))
      (is (= :model-declined (:overview-decline-reason out))))))

;; =============================================================================
;; 3. The real graph, executed
;; =============================================================================

(def ^:private stub-calls (atom []))

(defn- stub! [skill-id category inputs outputs execute]
  (skills/register-skill!
    {:metadata {:skill-id skill-id
                :name (str "Stub " skill-id)
                :description (str "Stub for " skill-id)
                :category category
                :inputs inputs
                :outputs outputs
                :parameters {}
                :required-services #{}}
     :execute (fn [ctx]
                (swap! stub-calls conj skill-id)
                (execute ctx))}))

(defn- register-graph-stubs!
  "Stub exactly the four skills that would otherwise reach the network or an
   LLM. The gate, the finalize step, the graph, the runner and the condition
   are all the real ones."
  [{:keys [chunks context-docs synthesis-outputs]}]
  (stub! :builtin/query-planner :query-transformation
         [:query :conversation-history :phrases-collection] [:queries :user-intent]
         (fn [_] (skills/success-result {:queries ["q1" "q2"] :user-intent "intent"} {})))
  (stub! :builtin/retrieval :retrieval
         [:queries :user-intent :docs-collection :chunks-collection :phrases-collection]
         [:chunks :search-attribution]
         (fn [_] (skills/success-result {:chunks chunks :search-attribution {}} {})))
  (stub! :builtin/rerank :reranking
         [:chunks :query :docs-collection] [:chunks :context-docs]
         (fn [_] (skills/success-result {:chunks chunks :context-docs context-docs} {})))
  (stub! :builtin/overview-synthesis :generation
         [:query :context-docs]
         [:overview-response :citations :citation-index :citation-validation
          :insufficient-context :insufficient-context-signal :prompts]
         (fn [_] (skills/success-result synthesis-outputs {}))))

(defn- with-clean-registry [f]
  (reset! stub-calls [])
  (skills/clear-registry!)
  ;; Real gate + finalize (and the real overview-synthesis, which the stub
  ;; below replaces per-test).
  (overview/register!)
  (with-redefs [ctx/resolve-all-services (fn [_] {:typesense {} :azure-openai {} :colbert {}})]
    (f))
  (skills/clear-registry!))

(use-fixtures :each with-clean-registry)

(def ^:private graph-inputs
  {:user-query "Hva er Altinn Autorisasjon?"
   :conversation-history []
   :docs-collection "docs"
   :chunks-collection "chunks"
   :phrases-collection "phrases"})

(deftest graph-produces-an-overview-on-thick-evidence
  (register-graph-stubs!
    (assoc thick-evidence
           :synthesis-outputs
           {:overview-response "Autorisasjon styrer tilgang [1]. Det gjelder alle tjenester [2]."
            :citations [{:index 1 :chunk-id "c1"} {:index 2 :chunk-id "c2"}]
            :citation-index {1 "c1" 2 "c2"}
            :citation-validation {:all-valid? true}
            :insufficient-context false}))
  (let [{:keys [outputs]} (runner/run-graph overview/ai-overview-graph graph-inputs
                                            {:tenant "test"})]
    (is (false? (:overview-declined? outputs)))
    (is (= "Autorisasjon styrer tilgang [1]. Det gjelder alle tjenester [2]."
           (:response outputs)))
    (is (= {1 "c1" 2 "c2"} (:citation-index outputs))
        "citation-index must survive to the caller — it is what makes [N] one-hop checkable")
    (is (some #{:builtin/overview-synthesis} @stub-calls)
        "synthesis should run when evidence is thick")))

(deftest graph-declines-without-calling-the-llm
  (testing "thin retrieval skips the synthesis step entirely"
    (register-graph-stubs!
      {:chunks [(mk-chunk "c1" 0.9)]
       :context-docs [(context-doc "c1")]
       :synthesis-outputs {:overview-response "should never be produced"
                           :citations [{:index 1 :chunk-id "c1"}]
                           :citation-index {1 "c1"}
                           :citation-validation {:all-valid? true}
                           :insufficient-context false}})
    (let [{:keys [outputs]} (runner/run-graph overview/ai-overview-graph graph-inputs
                                              {:tenant "test"})]
      (is (true? (:overview-declined? outputs)))
      (is (= :too-few-sources (:overview-decline-reason outputs)))
      (is (= "" (:response outputs)))
      (is (not (some #{:builtin/overview-synthesis} @stub-calls))
          "the whole point of a pre-synthesis gate is that no LLM call is made"))))

(deftest graph-suppresses-an-uncited-answer-that-was-generated
  (testing "the gate passed, the model answered, and the answer is still dropped"
    (register-graph-stubs!
      (assoc thick-evidence
             :synthesis-outputs
             {:overview-response "Autorisasjon styrer tilgang til tjenester."
              :citations []
              :citation-index {}
              :citation-validation {:all-valid? true}
              :insufficient-context false}))
    (let [{:keys [outputs]} (runner/run-graph overview/ai-overview-graph graph-inputs
                                              {:tenant "test"})]
      (is (some #{:builtin/overview-synthesis} @stub-calls))
      (is (true? (:overview-declined? outputs)))
      (is (= :uncited (:overview-decline-reason outputs)))
      (is (= "" (:response outputs))))))

(deftest graph-returns-the-underlying-results-even-when-it-declines
  (testing "an overview is presented AHEAD of the results, never instead of them"
    (register-graph-stubs!
      {:chunks [(mk-chunk "c1" 0.9)]
       :context-docs [(context-doc "c1")]
       :synthesis-outputs {}})
    (let [{:keys [outputs]} (runner/run-graph overview/ai-overview-graph graph-inputs
                                              {:tenant "test"})]
      (is (true? (:overview-declined? outputs)))
      (is (= 1 (count (:chunks outputs))) "retrieval results survive a declined overview")
      (is (= ["q1" "q2"] (:queries outputs)))
      (is (some? (:evidence-summary outputs))
          "the decline must be explainable without re-running retrieval"))))

(deftest gate-is-ordered-before-synthesis
  (testing "the gate->synthesis dependency is a real input edge, not just a :condition"
    ;; Regression. `topological-sort` derives order from `:inputs` refs only and
    ;; cannot see inside a `:condition` function. The first version of this
    ;; graph fed :overview straight from :rerank and relied on the condition
    ;; alone; the sort put :overview before :gate, the condition read an empty
    ;; step-outputs map, and EVERY overview was declined — while the graph read
    ;; correctly in source. Assert the ordering, not the intent.
    (let [order (vec (runner/topological-sort overview/ai-overview-graph))]
      (is (< (.indexOf order :gate) (.indexOf order :overview)))
      (is (< (.indexOf order :overview) (.indexOf order :finalize))))))

(deftest graph-has-exactly-one-producer-of-response
  (testing "no step other than :finalize may emit :response"
    ;; collect-outputs merges every step's outputs into one map, so two steps
    ;; emitting :response would leave the final value decided by map iteration
    ;; order. This asserts the invariant at the definition, not the run.
    (let [producers (->> (:steps overview/ai-overview-graph)
                         (filter (fn [step]
                                   (let [m (skills/get-skill (:skill step))]
                                     (some #{:response} (get-in m [:metadata :outputs])))))
                         (map :id))]
      (is (= [:finalize] (vec producers))))))
