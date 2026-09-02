(ns digdir.sweep.runner-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.sweep.invoke :as sweep-invoke]
            [digdir.eval.run-validity :as validity]
            [digdir.sweep.runner :as runner]))

(deftest retrieved-chunk-ids-reads-reranked-then-falls-back
  (testing "Prefers :diagnostics :outputs :workspace-final :reranked-chunks
            (rank-ordered, with :chunk_id), then top-level :chunks, then
            workspace :chunks array-map keyed by chunk-id."
    (is (= ["r1" "r2"]
           (runner/retrieved-chunk-ids
             {:diagnostics
              {:outputs
               {:workspace-final
                {:reranked-chunks [{:chunk_id "r1" :rerank-rank 1}
                                   {:chunk_id "r2" :rerank-rank 2}]}}}})))
    ;; Falls back to top-level :chunks (kebab key)
    (is (= ["a" "b"]
           (runner/retrieved-chunk-ids
             {:chunks [{:chunk-id "a"} {:chunk-id "b"}]})))
    ;; Final fallback: workspace :chunks as {id → data} map
    (is (= ["x" "y"]
           (sort (runner/retrieved-chunk-ids
                   {:diagnostics
                    {:outputs
                     {:workspace-final
                      {:chunks {"x" {:doc 1} "y" {:doc 2}}}}}}))))
    (is (= [] (runner/retrieved-chunk-ids {})))
    ;; Dedup preserves order.
    (is (= ["r1" "r2"]
           (runner/retrieved-chunk-ids
             {:diagnostics
              {:outputs
               {:workspace-final
                {:reranked-chunks [{:chunk_id "r1"} {:chunk_id "r2"} {:chunk_id "r1"}]}}}})))))

(deftest cited-chunk-ids-orders-by-index
  (testing "Returns chunk-ids in citation-index order."
    (is (= ["b" "a" "c"]
           (runner/cited-chunk-ids
             {:diagnostics
              {:outputs
               {:workspace-final
                {:citations [{:index 2 :chunk-id "a"}
                             {:index 1 :chunk-id "b"}
                             {:index 3 :chunk-id "c"}]}}}})))
    (is (= [] (runner/cited-chunk-ids {})))))

(deftest score-run-recall-and-hit-rate
  (testing "recall-at-k and answer-substring-hit? scoring."
    (let [question {:id "q1"
                    :query "Hvor mange?"
                    :golden-chunk-ids ["a" "b"]
                    :expected-answer-pattern "(?i)\\b326\\b"
                    :grounding-mode :chunks+answer}
          result {:status :complete
                  :response "Digdir hadde 326 utforte arsverk."
                  :diagnostics {:execution-metadata {:steps-executed 3}
                                ;; Usage now lives per-LLM-call inside
                                ;; :stage-timings (agent-token-totals
                                ;; sums them); the old :outputs :usage
                                ;; field was retired when the :usage
                                ;; CSV column landed.
                                :stage-timings [{:stage :synthesis
                                                 :usage {:prompt_tokens 100
                                                         :completion_tokens 25
                                                         :total_tokens 125}}]
                                :outputs {:workspace-final
                                          {:reranked-chunks
                                           [{:chunk_id "c"} {:chunk_id "a"}
                                            {:chunk_id "d"} {:chunk_id "b"}
                                            {:chunk_id "e"} {:chunk_id "f"}]
                                           :citations
                                           [{:index 1 :chunk-id "a"}
                                            {:index 2 :chunk-id "b"}]}}}}
          scored (runner/score-run question result)]
      ;; Retrieved order: c a d b e f. Top-5 = c,a,d,b,e. Golden a,b
      ;; both appear → recall@5 = 2/2 = 1.0.
      (is (= 1.0 (:recall-at-5 scored)))
      (is (= 1.0 (:recall-at-10 scored)))
      (is (true? (:answer-substring-hit? scored)))
      (is (= "c;a;d;b;e;f" (:retrieved-chunk-ids scored)))
      (is (= "a;b" (:cited-chunk-ids scored)))
      (is (= "a;b" (:expected-chunk-ids scored)))
      (is (= 6 (:n-retrieved scored)))
      (is (= 2 (:n-cited scored)))
      (is (= 2 (:n-expected scored)))
      (is (= 1.0 (:citation-recall scored)))
      (is (= 100 (:prompt-tokens scored)))
      (is (= 3 (:steps-executed scored))))))

(deftest score-run-handles-empty-golden
  (testing "When the row has no golden-chunk-ids (e.g. :answer-only),
            recall metrics are nil but answer-hit still scored."
    (let [question {:id "q-ans"
                    :golden-chunk-ids []
                    :expected-answer-pattern "(?i)foo"
                    :grounding-mode :answer-only}
          result {:status :complete
                  :response "foo bar"
                  :chunks [{:chunk-id "x"}]
                  :diagnostics {}}
          scored (runner/score-run question result)]
      (is (nil? (:recall-at-5 scored)))
      (is (nil? (:recall-at-10 scored)))
      (is (true? (:answer-substring-hit? scored)))
      (is (= 0 (:n-expected scored))))))

(deftest score-run-missing-response
  (testing "Empty response with a pattern still produces a clean
            scored map (no NPE; :answer-substring-hit? is false)."
    (let [scored (runner/score-run
                   {:expected-answer-pattern "x" :golden-chunk-ids []}
                   {:response "" :chunks []})]
      (is (false? (:answer-substring-hit? scored))))))

;; ---------------------------------------------------------------------------
;; Agent-path behavior signals
;; ---------------------------------------------------------------------------

(defn- wf-result [wf] {:diagnostics {:outputs {:workspace-final wf}}})

(deftest search-pass-count-counts-search-history
  (is (= 0 (runner/search-pass-count (wf-result {}))))
  (is (= 2 (runner/search-pass-count
             (wf-result {:search-history [{:queries ["a"]} {:queries ["b"]}]})))))

(deftest insufficiency-fired-detects-gate-and-flag
  (testing "false when every sufficiency decision is :sufficient and no flag"
    (is (false? (runner/insufficiency-fired?
                  (wf-result {:sufficiency-decisions [{:status :sufficient}]})))))
  (testing "true when any decision is non-:sufficient"
    (is (true? (runner/insufficiency-fired?
                 (wf-result {:sufficiency-decisions [{:status :insufficient}
                                                     {:status :sufficient}]})))))
  (testing "true when the explicit-insufficient flag is set"
    (is (true? (runner/insufficiency-fired?
                 (wf-result {:last-generated-explicitly-insufficient? true}))))))

(deftest enrichment-hit-count-sums-attribution
  (is (= 0 (runner/enrichment-hit-count (wf-result {:search-history [{:queries ["a"]}]}))))
  (is (= 5 (runner/enrichment-hit-count
             (wf-result {:search-history
                         [{:attribution {:enrichment-hits-by-type {:hypothetical-questions 3}}}
                          {:attribution {:enrichment-hits-by-type {:hypothetical-questions 2}}}]})))))

(deftest refinement-corpus-grounded-only-when-researched
  (testing "nil (N/A) when there was no re-search"
    (is (nil? (runner/refinement-corpus-grounded?
                (wf-result {:search-history [{:queries ["altinn"]}]
                            :last-planner-phrases ["altinn 3 juni 2020"]}))))
    (is (nil? (runner/refinement-corpus-grounded? (wf-result {})))))
  (testing "true when a later pass reused a planner corpus phrase (case-insensitive)"
    (is (true? (runner/refinement-corpus-grounded?
                 (wf-result {:search-history [{:queries ["altinn"]}
                                              {:queries ["Altinn 3 juni 2020"]}]
                             :last-planner-phrases ["altinn 3 juni 2020" "første versjon"]})))))
  (testing "false when later passes collapsed to generic queries"
    (is (false? (runner/refinement-corpus-grounded?
                  (wf-result {:search-history [{:queries ["altinn"]}
                                               {:queries ["altinn dato"]}]
                              :last-planner-phrases ["altinn 3 juni 2020"]}))))))

(deftest write-csv-quotes-fields-with-commas-or-newlines
  (testing "csv-escape produces RFC-4180-ish output."
    (let [tmp (java.io.File/createTempFile "runs-" ".csv")
          path (.getAbsolutePath tmp)]
      (try
        (with-redefs [runner/csv-columns [:a :b :c]]
          (runner/write-csv! path
            [{:a "plain" :b "has, comma" :c "has \"quote\""}
             {:a "newline\nhere" :b 42 :c true}]))
        (let [text (slurp path)]
          (is (str/includes? text "a,b,c\n"))
          (is (str/includes? text "plain,\"has, comma\""))
          (is (str/includes? text "\"has \"\"quote\"\"\""))
          (is (str/includes? text "\"newline\nhere\",42,true")))
        (finally (.delete tmp))))))

(deftest run-matrix-stubs-invoke-and-writes-csv
  (testing "End-to-end mock: stub invoke-with-clarification-loop,
            stub the dataset resolver, run a 2-config × 2-question
            matrix, confirm the CSV has 4 rows + a header."
    (let [tmp-dir (.getAbsolutePath
                    (doto (java.io.File/createTempFile "sweep-" "")
                      (.delete) (.mkdirs)))]
      (try
        (with-redefs [runner/resolve-dataset-config!
                      (fn [_]
                        {:dataset-config {}
                         :collections {:docs-collection "d"
                                       :chunks-collection "c"
                                       :phrases-collection "p"}})
                      sweep-invoke/invoke-with-clarification-loop
                      (fn [args]
                        {:status :complete
                         :response (str "answer for: " (:user-query args))
                         :chunks [{:chunk-id "chunk-a"}]
                         :clarification-rounds 0
                         :terminal-clarification? false})]
          (let [result (runner/run-matrix
                         {:configs [{:id "c1" :skill-graph-id :builtin/g1}
                                    {:id "c2" :skill-graph-id :builtin/g2}]
                          :questions [{:id "q1"
                                       :query "How?"
                                       :dataset :public-docs
                                       :source :new
                                       :tags #{:simple}
                                       :golden-chunk-ids ["chunk-a"]
                                       :expected-answer-pattern "(?i)answer"}
                                      {:id "q2"
                                       :query "What?"
                                       :dataset :public-docs
                                       :source :new
                                       :tags #{:simple}
                                       :golden-chunk-ids []
                                       :expected-answer-pattern "(?i)answer"
                                       :grounding-mode :answer-only}]
                          :repeats 1
                          :execution-scope {:tenant "digdir" :dataset-config-key "default"}
                          :out-dir tmp-dir})]
            (is (= 4 (count (:rows result))))
            (is (every? #(= "complete" (:status %)) (:rows result)))
            (is (every? #(true? (:answer-substring-hit? %)) (:rows result)))
            (is (.exists (io/file (str tmp-dir "/runs.csv"))))
            (is (.exists (io/file (str tmp-dir "/matrix.edn"))))
            (let [csv (slurp (str tmp-dir "/runs.csv"))
                  lines (str/split-lines csv)]
              (is (= 5 (count lines)) "1 header + 4 data rows")
              (is (str/starts-with? (first lines) "run-id,timestamp,config-id,question-id"))
              (is (some #(str/includes? % "c1,q1") lines))
              (is (some #(str/includes? % "c2,q2") lines)))))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp-dir)))]
            (.delete f)))))))

;; -----------------------------------------------------------------------------
;; Latency decomposition (#25)
;; -----------------------------------------------------------------------------

(def ^:private latency-timings
  "Realistic stage-timings, using the shapes the agent actually emits:
   `tool-stage-info` produces snake_case (:read_chunks, :rerank_results,
   :plan_queries) while the loop produces kebab-case (:agent-llm), and
   `:usage` is attached only when the provider returned it."
  [{:stage :search :duration-ms 120 :status :ok}
   {:stage :rerank_results :duration-ms 400 :status :ok}
   {:stage :read_chunks :duration-ms 80 :status :ok}
   {:stage :agent-llm :iteration 1 :duration-ms 9000
    :usage {:prompt_tokens 1200 :completion_tokens 300}}
   {:stage :plan_queries :duration-ms 2000
    :usage {:prompt_tokens 200 :completion_tokens 40}}
   {:stage :budget-refund :duration-ms 5}])

(deftest stage-duration-totals-splits-model-independent-io-from-llm
  (testing "I/O, LLM and unclassified are summed separately"
    (let [t (runner/stage-duration-totals
              {:diagnostics {:stage-timings latency-timings
                             :execution-metadata {:total-duration-ms 11605}}})]
      (is (= 600 (:io-ms t)) "search 120 + rerank_results 400 + read_chunks 80")
      (is (= 11000 (:llm-ms t)) "agent-llm 9000 + plan_queries 2000")
      (is (= 5 (:other-ms t)) ":budget-refund is neither, and must stay visible")
      (is (= 120 (:io-search-ms t)))
      (is (= 400 (:io-rerank-ms t)))
      (is (= 80 (:io-tool-ms t)))
      (is (= 3 (:io-call-count t)))
      (is (= 2 (:llm-call-count t)))
      (is (= 11605 (:total-duration-ms t)))))

  (testing "nil without timings, so the CSV renders empty rather than zero"
    ;; `no data` and `summed to zero` must stay distinguishable — a timeout
    ;; row that never produced agent output would otherwise read as an
    ;; instantaneous run.
    (is (nil? (runner/stage-duration-totals {:diagnostics {}})))))

(deftest snake-case-io-stages-are-classified-as-io
  ;; THE REGRESSION THIS EXISTS FOR. The stage keywords are mixed kebab- and
  ;; snake_case. Enumerating them with a [a-z-]+ character class silently
  ;; drops every snake_case stage, and an I/O set built from that enumeration
  ;; omits :read_chunks and :rerank_results — the two largest I/O items.
  ;; They would land in :other-ms and the model-independent floor would be
  ;; understated, which for a "is the target reachable at all" question is
  ;; the dangerous direction.
  (testing ":read_chunks and :rerank_results count as I/O, not as other"
    (let [t (runner/stage-duration-totals
              {:diagnostics {:stage-timings
                             [{:stage :read_chunks :duration-ms 50}
                              {:stage :rerank_results :duration-ms 70}]}})]
      (is (= 120 (:io-ms t)))
      (is (= 0 (:other-ms t)) "these must not fall through to unclassified")))

  (testing ":plan_queries is an LLM call despite living inside retrieval"
    ;; The query planner calls a model. This is the concrete reason
    ;; "everything before synthesis" is not "model-independent".
    (let [t (runner/stage-duration-totals
              {:diagnostics {:stage-timings
                             [{:stage :plan_queries :duration-ms 900
                               :usage {:prompt_tokens 10 :completion_tokens 5}}]}})]
      (is (= 0 (:io-ms t)) "the planner is not I/O")
      (is (= 900 (:llm-ms t))))))

(deftest corpus-provenance-reaches-the-csv
  ;; resolve-dataset-config! computed these all along and the runner threw
  ;; them away, so a finished sweep could not be audited for WHICH CORPUS it
  ;; measured — and for at least one sweep the config DB that could have
  ;; answered it afterwards had already been deleted. Unrecoverable, not
  ;; merely unrecorded.
  (testing "the resolved corpus identity is persisted on every row"
    (let [row {:run-id "r1"
               :typesense-uri "http://typesense-test:8108"
               :docs-collection "website_documents_ab897fbdedfa"
               :chunks-collection "website_chunks_ab897fbdedfa"
               :phrases-collection "website_phrases_pruned_c20v1"}
          w (java.io.StringWriter.)]
      (runner/write-csv-header! w)
      (runner/write-csv-row! w row)
      (let [[header line] (str/split-lines (str w))]
        ;; Positional splitting is valid HERE only because this row contains
        ;; no commas, so assert that precondition rather than assume it. A
        ;; real runs.csv carries quoted response text with commas in it and
        ;; must be read with a CSV reader — splitting on the delimiter is a
        ;; text operation and the field boundary is a parse-level fact.
        (is (not-any? #(str/includes? (str %) ",") (vals row))
            "this fixture must stay comma-free or the assertions below lie")
        (let [cols (str/split header #",")
              cells (str/split line #"," -1)
              at (fn [k] (nth cells (.indexOf cols (name k))))]
          (is (= "http://typesense-test:8108" (at :typesense-uri)))
          (is (= "website_documents_ab897fbdedfa" (at :docs-collection)))
          (is (= "website_chunks_ab897fbdedfa" (at :chunks-collection)))
          (is (= "website_phrases_pruned_c20v1" (at :phrases-collection))
              "the phrases override varies per config; a sweep-level constant
               would mis-record the A/B arm that used a pruned clone")))))

  (testing "the provenance columns are actually in the column list"
    ;; Guards the wiring rather than the writer: a column dropped from
    ;; csv-columns would make every assertion above vacuous, since the
    ;; writer only emits what that list names.
    (let [cols (set runner/csv-columns)]
      (doseq [k [:typesense-uri :docs-collection :chunks-collection :phrases-collection]]
        (is (contains? cols k) (str k " is missing from csv-columns"))))))

(deftest first-chunk-timer-times-the-first-prose-chunk-only
  ;; TTFT has never been measured on any path in this repository: until the
  ;; 6-arity call-llm landed, the sweep path made a blocking request that
  ;; emitted nothing to time. This times the first :response/chunk.
  (testing "only the first chunk sets the clock; later chunks only count"
    (let [{:keys [progress-fn snapshot]} (runner/make-first-chunk-timer
                                           (System/currentTimeMillis))]
      ;; Asserted field-by-field rather than as an exact map. Whole-map
      ;; equality here fails on any ADDED key, including a correct one — it
      ;; went red when :first-chunk-text was added, which is an anti-guard in
      ;; the #323 sense: a check whose passing condition the right fix
      ;; violates. The fields below are the invariant; the map shape is not.
      (is (nil? (:ttft-ms (snapshot))) "nothing streamed yet — nil, not zero")
      (is (zero? (:response-chunk-count (snapshot))))
      (is (nil? (:first-chunk-text (snapshot)))
          "and nothing to attribute the clock to either")
      (progress-fn {:event :response/chunk :delta "Digdir "})
      (let [first-reading (:ttft-ms (snapshot))]
        (is (some? first-reading))
          ;; POSITIVE assertion on the field, not merely a nil check (#429).
          ;; The nil assertions above pass whether the key is present-and-nil
          ;; or ABSENT -- (:k m) cannot tell those apart -- so before this line
          ;; :first-chunk-text could be deleted from the producer outright and
          ;; the whole suite stayed green. Measured, not reasoned.
          (is (= "Digdir " (:first-chunk-text (snapshot)))
              "the first chunk's text is captured, not merely counted")
        (progress-fn {:event :response/chunk :delta "hadde 326 årsverk."})
        (is (= first-reading (:ttft-ms (snapshot)))
            "a later chunk must not move the clock")
          ;; The field names what the clock was attributed to, so a later chunk
          ;; must not overwrite it -- the same invariant as the ttft reading
          ;; above, on the value that makes a nil ttft diagnosable.
          (is (= "Digdir " (:first-chunk-text (snapshot)))
              "a later chunk must not replace the attributed text")
        (is (= 2 (:response-chunk-count (snapshot)))))))

  (testing "non-chunk events are ignored"
    ;; The agent emits many event types. Timing the first event of ANY kind
    ;; would measure the first stage transition, not the first visible token.
    (let [{:keys [progress-fn snapshot]} (runner/make-first-chunk-timer
                                           (System/currentTimeMillis))]
      (progress-fn {:event :graph/completed :steps-executed 3})
      (progress-fn {:event :request/started :query "q"})
      (progress-fn {:event :response/finalized})
      (is (nil? (:ttft-ms (snapshot)))
          "no prose chunk has been emitted, so there is nothing to time")
      (is (zero? (:response-chunk-count (snapshot))))
      (is (nil? (:first-chunk-text (snapshot))))))

  (testing "a nil ttft is diagnosable rather than ambiguous"
    ;; emit-progress! swallows callback exceptions, so a broken timer and a
    ;; non-streaming path both leave ttft nil. The count separates them:
    ;; 0 means nothing streamed, which is what a blocking path looks like.
    (let [{:keys [snapshot]} (runner/make-first-chunk-timer (System/currentTimeMillis))
          s (snapshot)]
      (is (nil? (:ttft-ms s)))
      (is (zero? (:response-chunk-count s))
          "count 0 is the signature of a non-streaming path, NOT of an instant response")))

  (testing "the columns are in csv-columns, or the values are never written"
    (let [cols (set runner/csv-columns)]
      (is (contains? cols :ttft-ms))
      (is (contains? cols :response-chunk-count)))))

(deftest served-models-records-what-answered-not-what-was-asked
  ;; The harness recorded its results but not its conditions: the Kimi
  ;; figures were produced against an endpoint reached through a tunnel
  ;; nobody wrote down, so they cannot be re-measured. The configured name
  ;; is what we ASKED for; :llm-model on the response is what ANSWERED.
  (testing "distinct served model ids, in order, joined"
    (let [result {:diagnostics
                  {:stage-timings
                   [{:stage :agent-llm :llm-model "moonshotai/Kimi-K2.7-Code"}
                    {:stage :search}
                    {:stage :agent-llm :llm-model "moonshotai/Kimi-K2.7-Code"}
                    {:stage :generate_response :llm-model "google/gemma-4-26b-a4b"}]}}]
      (is (= "moonshotai/Kimi-K2.7-Code;google/gemma-4-26b-a4b"
             (runner/served-models result))
          "repeats collapse; a second MODEL does not — a sub-skill served by a
           different model is exactly what this column exists to surface")))

  (testing "nil when nothing reported a model"
    ;; Empty cell, not an empty string: `no data` must stay distinguishable
    ;; from `the server returned no model`.
    (is (nil? (runner/served-models {:diagnostics {:stage-timings [{:stage :search}]}})))
    (is (nil? (runner/served-models {:diagnostics {}}))))

  (testing "the endpoint provenance columns are in csv-columns"
    (let [cols (set runner/csv-columns)]
      (doseq [k [:llm-uri :llm-model-requested :llm-model-served]]
        (is (contains? cols k) (str k " missing from csv-columns"))))))

(deftest model-comparability-voids-a-comparison-across-different-models
  ;; A vendor can upgrade a preview model underneath a deployment name that
  ;; never changes — no commit, no config change, no log line. The only field
  ;; that can detect it is the one the SERVER reports, and it only helps if
  ;; asserted BEFORE the delta is computed rather than after it is quoted.
  (testing "same served model across both sets is comparable"
    (let [a [{:llm-model-served "qwen/qwen3.6-35b-a3b"}]
          b [{:llm-model-served "qwen/qwen3.6-35b-a3b"}]]
      (is (= :comparable (:status (runner/model-comparability a b))))
      (is (= "qwen/qwen3.6-35b-a3b" (runner/assert-comparable-models! a b)))))

  (testing "different models VOID the comparison rather than inviting reconciliation"
    (let [a [{:llm-model-served "qwen/qwen3.6-35b-a3b"}]
          b [{:llm-model-served "moonshotai/Kimi-K2.7-Code"}]]
      (is (= :void (:status (runner/model-comparability a b))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"VOID"
                            (runner/assert-comparable-models! a b)))))

  (testing "no reported model is UNVERIFIABLE, not comparable"
    ;; Absence of evidence is not evidence of sameness. Run sets predating the
    ;; column would otherwise pass a check they cannot support.
    (let [old [{:elapsed-ms 114000} {:elapsed-ms 120000}]]
      (is (= :unverifiable (:status (runner/model-comparability old old))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"UNVERIFIABLE"
                            (runner/assert-comparable-models! old old)))))

  (testing "survives the round-trip through the artifact (string keys)"
    ;; A runs.csv read back has STRING keys. A check that only works on
    ;; in-memory rows would never fire where comparisons actually happen.
    (let [from-csv [{"llm-model-served" "qwen/qwen3.6-35b-a3b"}]
          other    [{"llm-model-served" "google/gemma-4-26b-a4b"}]]
      (is (= :comparable (:status (runner/model-comparability from-csv))))
      (is (= :void (:status (runner/model-comparability from-csv other))))))

  (testing "a row listing several models splits rather than comparing whole strings"
    ;; served-models joins with ';' when a sub-skill ran on a different model.
    ;; Comparing the joined string would call two identical model sets
    ;; different merely because they were listed in another order.
    (let [a [{:llm-model-served "a-model;b-model"}]
          b [{:llm-model-served "b-model"} {:llm-model-served "a-model"}]]
      (is (= #{"a-model" "b-model"} (runner/reported-models a)))
      (is (= :void (:status (runner/model-comparability b b)))
          "two DIFFERENT models across b's rows is void, however they are spelled")
      (is (= (runner/reported-models a) (runner/reported-models b))))))

;; -----------------------------------------------------------------------------
;; Gate parity between the two totals (#284)
;; -----------------------------------------------------------------------------

(def ^:private timings-no-llm
  "Stages ran; nothing ever reached an LLM, so no entry carries :usage.
   This is the shape of a run that failed before its first model call — the
   entire population of #276."
  {:diagnostics {:stage-timings [{:stage :search :duration-ms 12}
                                 {:stage :budget-refund :duration-ms 3}]}})

(def ^:private timings-with-llm
  {:diagnostics {:stage-timings [{:stage :search :duration-ms 12}
                                 {:stage :agent-llm :duration-ms 900
                                  :usage {:prompt_tokens 10 :completion_tokens 4}}]}})

(deftest token-totals-and-duration-totals-share-one-gate
  ;; THE DEFECT. Both read find-stage-timings; agent-token-totals additionally
  ;; gated on (seq usages), so it returned nil — blank cells — for a run that
  ;; executed stages and reached no LLM, while stage-duration-totals returned
  ;; zeros for the same input. Two functions, one source, two answers.
  (testing "both return a map, or both nil, for the same result"
    (doseq [[label result] [["no timings at all" {:diagnostics {}}]
                            ["timings, no LLM call" timings-no-llm]
                            ["timings with an LLM call" timings-with-llm]]]
      (is (= (some? (runner/agent-token-totals result))
             (some? (runner/stage-duration-totals result)))
          (str "gates disagree for: " label))))

  (testing "a run that reached no LLM records a PRESENT ZERO, not a blank"
    (let [t (runner/agent-token-totals timings-no-llm)]
      (is (some? t) "stage timings exist, so this is a measured fact not a missing one")
      (is (= 0 (:llm-calls t)))
      (is (= 0 (:prompt-tokens t)))
      (is (= 0 (:completion-tokens t)))))

  (testing "no stage timings at all still yields nil, so the cell stays blank"
    ;; Unchanged on purpose. `the run produced nothing` and `the run ran and
    ;; made no call` are different facts and must not collapse.
    (is (nil? (runner/agent-token-totals {:diagnostics {}})))
    (is (nil? (runner/stage-duration-totals {:diagnostics {}}))))

  (testing "a real LLM call still counts"
    (is (= 1 (:llm-calls (runner/agent-token-totals timings-with-llm))))))

(deftest the-fix-does-not-make-historical-artifacts-unreadable
  ;; THE RETROACTIVE RISK. Existing runs.csv files keep their blanks. The fix
  ;; is for FUTURE rows; run-validity must still read the past correctly.
  (testing "historical row: llm-calls BLANK, llm-ms \"0\" — still :no-llm-call"
    ;; Exactly the shape of both #275 artifacts, which is why run-validity's
    ;; llm-ms fallback must survive this change rather than be retired by it.
    (is (= :no-llm-call (validity/classify {"llm-calls" "" "llm-ms" "0"}))))

  (testing "row predating BOTH columns is still :unknown, not condemned"
    (is (= :unknown (validity/classify {"elapsed-ms" "114000"}))))

  (testing "future row written after this fix: llm-calls \"0\" is now the direct answer"
    ;; The point of #284 — the primary key starts working, so the llm-ms
    ;; fallback becomes belt-and-braces rather than the only thing holding
    ;; the guard up.
    (is (= :no-llm-call (validity/classify {"llm-calls" "0" "llm-ms" "0"})))
    (is (= :measured (validity/classify {"llm-calls" "2" "llm-ms" "9000"})))))
