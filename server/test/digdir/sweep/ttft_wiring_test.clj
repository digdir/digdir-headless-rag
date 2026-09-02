(ns digdir.sweep.ttft-wiring-test
  "#323 — the TTFT timer was present, injected on every run, and read by
   nothing.

   `runner/run-single` put its `:progress-fn` at
   `skill-params [:builtin/agent :progress-fn]`. The bundled graph reads it
   from `[:opts :progress-fn]` (iteration_bundled.clj, graphs.clj), which
   `invoke-rag` populates only from a TOP-LEVEL `:progress-fn` argument. A
   grep for the skill-params location returned exactly one hit in the whole
   tree: the write. So `call-llm` saw nil, took the blocking branch, and
   `ttft-ms` came back nil on all 16 runs of the #25 latency sweep.

   Nothing failed. The sweep reported 16/16 complete.

   These tests pin the whole chain — runner puts it top-level, invoke-rag
   lifts it to `[:opts :progress-fn]`, and a callee that streams actually
   moves the clock. The missing test was the defect; the missing wiring was
   only its symptom."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.skills.api :as skills-api]
            [digdir.skills.events :as events]
            [digdir.skills.invoke :as invoke]
            [digdir.sweep.invoke :as sweep-invoke]
            [digdir.sweep.runner :as runner]))

(def ^:private question
  {:id "q-ttft" :query "Hvilke tilstander kan en filoverføring befinne seg i?"
   :tags [] :expected-chunk-ids []})

(def ^:private base-args
  {:config {:id "deployed" :skill-params {:builtin/agent {:search-snippets true}}}
   :question question
   :collections {:docs-collection "d" :chunks-collection "c" :phrases-collection "p"}
   :execution-scope {:tenant "digdir" :dataset-config-key "default"}
   :judge? false})

(def ^:private ok-result
  {:status :complete :response "Svaret er Oslo." :chunks []
   :clarification-rounds 0 :terminal-clarification? false})

(deftest progress-fn-goes-top-level-not-into-skill-params
  (let [!args (atom nil)]
    (with-redefs [sweep-invoke/invoke-with-clarification-loop
                  (fn [args] (reset! !args args) ok-result)]
      (runner/run-single base-args))
    (testing "the runner hands the timer to the argument invoke-rag actually reads"
      (is (fn? (:progress-fn @!args))
          "top-level :progress-fn — this is what invoke-rag lifts into [:opts :progress-fn]"))
    ;; DELIBERATELY NOT ASSERTED: that the timer is ABSENT from
    ;; skill-params [:builtin/agent :progress-fn].
    ;;
    ;; That assertion was here, and it was an anti-guard (#323 row 10): a check
    ;; whose passing condition the right fix violates is broken, not strict.
    ;; Say its negative claim out loud — "this fails iff the timer also appears
    ;; under skill-params" — and a correct implementation that does exactly
    ;; that is easy to name: passing it in both places for a future consumer
    ;; that reads skill-params. Forbidding the dead location is over-specified;
    ;; what matters is that it also arrives at the live one, which the positive
    ;; assertion above and `ttft-is-recorded-when-the-callee-streams` cover.
    (testing "the rest of the matrix's skill-params still arrive intact"
      (is (true? (get-in @!args [:skill-params :builtin/agent :search-snippets]))))))

(deftest ttft-is-recorded-when-the-callee-streams
  ;; The behavioural half. The stub reads :progress-fn from the SAME place
  ;; production does and fires two response chunks. If the timer is ever
  ;; re-parked somewhere unreachable, this goes red — which the original
  ;; wiring never did.
  (let [!args (atom nil)]
    (with-redefs [sweep-invoke/invoke-with-clarification-loop
                  (fn [{:keys [progress-fn] :as args}]
                    (reset! !args args)
                    (when progress-fn
                      ;; Built with the REAL constructor, not a hand-made map.
                      ;; The previous fixture used {:event ... :chunk "..."};
                      ;; production emits :delta. Every count assertion passed
                      ;; either way, so a stub that had drifted from the
                      ;; producer would have recorded nil text and stayed green
                      ;; — the defect this whole file exists to catch.
                      (progress-fn (events/response-chunk "Svaret "))
                      (progress-fn (events/response-chunk "er Oslo.")))
                    ok-result)]
      (let [row (runner/run-single base-args)]
        (is (= 2 (:response-chunk-count row))
            "count 0 would mean nothing streamed — the signal that caught #323")
        (is (number? (:ttft-ms row))
            "a nil here means NO STREAMING, never `instant`")
        (is (<= 0 (:ttft-ms row) (:elapsed-ms row))
            "and the first-chunk clock cannot exceed the run it is inside")
        (is (= "Svaret " (:first-chunk-text row))
            "the FIRST chunk's text, so ttft-ms can be read as answer-or-narration")
        (is (not= "er Oslo." (:first-chunk-text row))
            "and not the last — compare-and-set!, not reset!")))))

(deftest non-streaming-callee-leaves-ttft-nil-with-count-zero
  ;; The diagnosable pair, asserted rather than assumed: this is exactly the
  ;; shape all 16 runs produced, and it must stay distinguishable from a
  ;; broken timer (count > 0 with nil ttft).
  (with-redefs [sweep-invoke/invoke-with-clarification-loop
                (fn [_args] ok-result)]
    (let [row (runner/run-single base-args)]
      (is (nil? (:ttft-ms row)))
      (is (zero? (:response-chunk-count row))))))

(deftest invoke-rag-lifts-progress-fn-to-where-the-graph-reads-it
  ;; The production half of the seam. The bundled graph reads
  ;; [:opts :progress-fn] off :ambient-ctx-opts; nothing else reaches it.
  (let [!inputs (atom nil)
        !opts (atom nil)
        f (fn [_ev])]
    (with-redefs [skills-api/run-skill-graph
                  (fn [_graph-id inputs opts]
                    (reset! !inputs inputs)
                    (reset! !opts opts)
                    {:outputs {:response "ok"}})]
      (try
        (invoke/invoke-rag {:user-query "Hva er Maskinporten?"
                            :skill-graph-id :builtin/agent-rag-graph-bundled
                            :execution-scope {:tenant "digdir"
                                              :dataset-config-key "default"}
                            :progress-fn f})
        (catch Exception _ nil)))
    (is (identical? f (get-in @!inputs [:ambient-ctx-opts :opts :progress-fn]))
        "iteration_bundled.clj and graphs.clj read exactly this path")
    (is (identical? f (:progress-fn @!opts))
        "and the graph-runner opts carry it too")))
