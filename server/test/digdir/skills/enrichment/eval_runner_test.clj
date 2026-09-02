(ns digdir.skills.enrichment.eval-runner-test
  "Pins the scoring semantics the keep/revert verdict is computed from.

   `eval-runner` was promoted in slice 2a of #82 (#94) to replace
   `digdir.sweep.runner/run-matrix` on the production path, and its own
   docstring says the scoring helpers were reimplemented rather than shared
   precisely so they could be pinned by tests. They were not, so slice 2b
   pins them — the eval skill promoted in this slice reads every number
   below.

   The load-bearing assertion is `score-row-emits-exactly-the-verdict-contract`.
   `batch-verdict` reads five keys off each row and treats a missing one as a
   value rather than an error: drop `:retrieved-chunk-ids` and every chunk
   silently verdicts \"revert\"; misspell `:answer-substring-hit?` without the
   `?` and the answer-drop veto never fires. Asserting on values cannot see
   either failure — only asserting the whole key set can."
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.skills.api :as skills-api]
            [digdir.skills.enrichment.eval-runner :as runner]))

;; ---------------------------------------------------------------------------
;; retrieved-chunk-ids
;; ---------------------------------------------------------------------------

(defn- reranked-result [chunks]
  {:diagnostics {:outputs {:workspace-final {:reranked-chunks chunks}}}})

(deftest retrieved-chunk-ids-reads-rank-order-first
  (testing "the reranked list wins over :chunks — recall@k is measured on rank order"
    (let [result (assoc (reranked-result [{:chunk_id "a"} {:chunk_id "b"}])
                        :chunks [{:chunk_id "z"}])]
      (is (= ["a" "b"] (runner/retrieved-chunk-ids result)))))

  (testing "order is preserved and duplicates collapse to first occurrence"
    (is (= ["a" "b" "c"]
           (runner/retrieved-chunk-ids
             (reranked-result [{:chunk_id "a"} {:chunk_id "b"} {:chunk_id "a"} {:chunk_id "c"}])))))

  (testing "falls back to :chunks, then to the workspace chunk map"
    (is (= ["x"] (runner/retrieved-chunk-ids {:chunks [{:chunk_id "x"}]})))
    (is (= ["k"] (runner/retrieved-chunk-ids
                   {:diagnostics {:outputs {:workspace-final {:chunks {"k" {}}}}}}))))

  (testing "a result carrying no retrieval at all scores as empty, not nil"
    (is (= [] (runner/retrieved-chunk-ids {})))))

;; ---------------------------------------------------------------------------
;; recall-at-k
;; ---------------------------------------------------------------------------

(deftest recall-at-k-scores-the-first-k-only
  (testing "fraction of expected ids inside the window"
    (is (= 1.0 (runner/recall-at-k ["a" "b"] ["a" "b" "c"] 20)))
    (is (= 0.5 (runner/recall-at-k ["a" "b"] ["a" "z"] 20))))

  (testing "an id past k does not count — this is the whole point of the window"
    (let [retrieved (concat (repeat 20 "filler") ["gold"])]
      (is (= 0.0 (runner/recall-at-k ["gold"] retrieved 20)))
      (is (= 1.0 (runner/recall-at-k ["gold"] retrieved 21)))))

  (testing "nil when there is nothing to score against"
    ;; Not 0.0. A question with no goldens has no recall; scoring it zero
    ;; would drag a batch mean down with a number that means nothing.
    (is (nil? (runner/recall-at-k [] ["a"] 20)))
    (is (nil? (runner/recall-at-k nil ["a"] 20)))))

;; ---------------------------------------------------------------------------
;; answer-substring-hit?
;; ---------------------------------------------------------------------------

(deftest answer-substring-hit-is-a-regex-match
  (is (true? (runner/answer-substring-hit? "Dialog\\w+" "Se Dialogporten for mer")))
  (is (false? (runner/answer-substring-hit? "Dialogporten" "noe helt annet")))

  (testing "absent pattern or response is false, never an exception"
    (is (false? (runner/answer-substring-hit? nil "text")))
    (is (false? (runner/answer-substring-hit? "x" nil)))
    (is (false? (runner/answer-substring-hit? "x" "")))))

;; ---------------------------------------------------------------------------
;; score-row — the contract itself
;; ---------------------------------------------------------------------------

(def ^:private verdict-contract-keys
  "The five keys `digdir.skills.enrichment.batch-verdict` reads off a row,
   restated here deliberately. If someone renames one in the runner, this
   list is what disagrees with them."
  #{:question-id :config-id :recall-at-20 :answer-substring-hit? :retrieved-chunk-ids})

(deftest score-row-emits-exactly-the-verdict-contract
  (let [config {:id "enrichment-on"}
        question {:id "q1" :golden-chunk-ids ["gold"] :expected-answer-pattern "yes"}
        result (assoc (reranked-result [{:chunk_id "gold"} {:chunk_id "other"}])
                      :response "yes indeed")
        row (runner/score-row config question result)]

    (testing "every key the verdict reads is present"
      (is (empty? (remove (set (keys row)) verdict-contract-keys))
          (str "missing from the row: " (remove (set (keys row)) verdict-contract-keys)
               " — batch-verdict reads these as nil and returns a WRONG verdict "
               "rather than failing")))

    (testing "and the key set as a whole is what we think it is"
      (is (= (into verdict-contract-keys [:response :status]) (set (keys row)))
          "a key appearing or vanishing here changes what the dashboard and
           the verdict see; assert the set, not just the values"))

    (testing "values"
      (is (= "q1" (:question-id row)))
      (is (= "enrichment-on" (:config-id row)))
      (is (= "gold;other" (:retrieved-chunk-ids row)) "\";\"-joined, rank order")
      (is (= 1.0 (:recall-at-20 row)))
      (is (true? (:answer-substring-hit? row)))
      (is (= :ok (:status row))))))

(deftest score-row-marks-a-failed-run-rather-than-scoring-it
  (let [row (runner/score-row {:id "c"} {:id "q" :golden-chunk-ids ["g"]} {:error "boom"})]
    (is (= :error (:status row)))
    (is (= 0.0 (:recall-at-20 row)) "a run that failed retrieved nothing")))

;; ---------------------------------------------------------------------------
;; run-comparison
;; ---------------------------------------------------------------------------

(def ^:private one-config [{:id "off" :skill-graph-id :g :skill-params {}}])
(def ^:private one-question [{:id "q1" :query "hva?" :golden-chunk-ids ["gold"]}])

(deftest run-comparison-refuses-an-empty-matrix
  (testing "a comparison with no configs or no questions is a caller bug, not an empty result"
    (is (thrown? clojure.lang.ExceptionInfo
                 (runner/run-comparison {:configs [] :questions one-question})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (runner/run-comparison {:configs one-config :questions []})))))

(deftest run-comparison-produces-configs-x-questions-x-repeats
  (let [calls (atom [])]
    (with-redefs [skills-api/run-skill-graph
                  (fn [graph-id inputs opts]
                    (swap! calls conj {:graph-id graph-id :inputs inputs :opts opts})
                    (reranked-result [{:chunk_id "gold"}]))]
      (let [configs [{:id "off" :skill-graph-id :g :skill-params {:a 1}}
                     {:id "on" :skill-graph-id :g :skill-params {:a 2}}]
            questions [{:id "q1" :query "one" :golden-chunk-ids ["gold"]}
                       {:id "q2" :query "two" :golden-chunk-ids ["gold"]}]
            {:keys [rows]} (runner/run-comparison
                             {:configs configs :questions questions :repeats 3
                              :execution-scope {:tenant "t" :dataset-config-key "d" :agent-id "a"}})]
        (is (= 12 (count rows)) "2 configs x 2 questions x 3 repeats")
        (is (= 12 (count @calls)))
        (is (= #{"off" "on"} (set (map :config-id rows))))
        (is (= #{"q1" "q2"} (set (map :question-id rows))))

        (testing "the execution scope reaches the graph invocation"
          (let [{:keys [inputs opts]} (first @calls)]
            (is (= {:query "one"} inputs))
            (is (= "t" (:tenant opts)))
            (is (= "d" (:dataset-config-key opts)))
            (is (= "a" (:agent-id opts)))))

        (testing "per-config skill-params are what separate the two arms"
          (is (= #{{:a 1} {:a 2}} (set (map (comp :skill-params :opts) @calls)))))))))

(deftest run-comparison-survives-a-failing-run
  (testing "one bad question yields an error row instead of discarding the batch"
    (with-redefs [skills-api/run-skill-graph
                  (fn [_ inputs _]
                    (if (= "boom" (:query inputs))
                      (throw (ex-info "graph blew up" {}))
                      (reranked-result [{:chunk_id "gold"}])))]
      (let [{:keys [rows]} (runner/run-comparison
                             {:configs one-config
                              :questions [{:id "ok" :query "fine" :golden-chunk-ids ["gold"]}
                                          {:id "bad" :query "boom" :golden-chunk-ids ["gold"]}]})
            by-id (into {} (map (juxt :question-id identity)) rows)]
        (is (= 2 (count rows)))
        (is (= :ok (get-in by-id ["ok" :status])))
        (is (= :error (get-in by-id ["bad" :status])))
        (is (some? (get-in by-id ["bad" :error])))))))
