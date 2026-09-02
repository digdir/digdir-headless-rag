(ns digdir.skills.enrichment.eval-delta-test
  "Phase A — unit-level coverage for :builtin/enrichment-eval-suite.

   We don't drive a live LLM/Typesense pass in these tests — that's
   what the Phase B eval-gate run is for, and it costs minutes per
   case. Here we verify the three plumbing properties that have to
   hold for the skill to be useful as a thermometer:

   1. The skill is registered at namespace-load time.
   2. Its inputs translate into the cli-opts vector `diagnostics`
      already parses (so behavior between CLI and in-process callers
      converges at `agent-budget-benchmark`).
   3. The skill returns whatever `agent-budget-benchmark` returned,
      including the new `:summary :results :effective-params` keys
      and any error envelope.

   We stub `diagnostics/agent-budget-benchmark` so the test runs in
   milliseconds and tells us about the wiring, not the model."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.eval-delta :as eval-delta]
            [digdir.tools.diagnostics :as diagnostics]))

(use-fixtures :once
  (fn [t]
    ;; Registry is global; make sure our skill is registered even if a
    ;; preceding test cleared it.
    (eval-delta/register!)
    (t)))

(deftest skill-registered
  (testing ":builtin/enrichment-eval-suite is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-eval-suite)))))

(deftest cli-opts-shape
  (testing "build-cli-opts maps explicit inputs to flag pairs the CLI parser already knows"
    (let [opts (#'eval-delta/build-cli-opts
                {:suite-file "test/fixtures/agent/altinn3_lansert_stability.edn"
                 :graph-variant :bundled
                 :tenant-config-key "default"
                 :runtime-config-key nil
                 :fail-on-gate? false
                 :progress? false})]
      (is (vector? opts))
      (is (some #{"--suite"} opts))
      (is (some #{"test/fixtures/agent/altinn3_lansert_stability.edn"} opts))
      (is (some #{"--graph-variant"} opts))
      (is (some #{"bundled"} opts))
      (is (some #{"--fail-on-gate"} opts))
      (is (some #{"false"} opts))
      (is (some #{"--tenant-config-key"} opts))
      (is (some #{"default"} opts))
      (is (not (some #{"--progress"} opts))
          "Omits --progress when progress? is falsy")
      (is (not (some #{"--runtime-config-key"} opts))
          "Omits --runtime-config-key when nil"))))

(deftest passes-result-through-on-success
  (testing "On a successful diagnostics return, the skill wraps the result in success-result outputs"
    (let [stubbed-result {:summary {:cases 1 :current-pass 1 :relaxed-pass 1
                                    :gate-pass true :error-count 0}
                          :results [{:id "altinn3-lansert-when" :pass true}]
                          :effective-params {:suite "x" :graph-variant :bundled}}]
      (with-redefs [diagnostics/agent-budget-benchmark (fn [_target] stubbed-result)]
        (let [skill-result (eval-delta/execute-eval-suite
                            {:inputs {:tenant "digdir"
                                      :dataset-config-key "public-docs"
                                      :tenant-config-key "default"
                                      :agent-id "builtin/agent-rag-agent"
                                      :suite-file "test/fixtures/agent/altinn3_lansert_stability.edn"
                                      :graph-variant :bundled
                                      :fail-on-gate? false}})
              outputs (skills/get-result-outputs skill-result)]
          (is (skills/result-success? skill-result))
          (is (= stubbed-result outputs)
              "Outputs are the diagnostics result verbatim. (The top-level :gate-pass mirror was removed once the runner gained n-element refs and `[:eval :summary :gate-pass]` works directly.)"))))))

(deftest surfaces-benchmark-gate-failure
  (testing "When diagnostics throws a benchmark-gate-failure ex-info, the skill returns {:gate-failed? true :summary ...} instead of bubbling"
    (let [summary {:cases 1 :gate-pass false}]
      (with-redefs [diagnostics/agent-budget-benchmark
                    (fn [_target]
                      (throw (ex-info "gate failed"
                                      {:summary summary
                                       :benchmark-gate-failure true})))]
        (let [skill-result (eval-delta/execute-eval-suite
                            {:inputs {:tenant "digdir"
                                      :dataset-config-key "public-docs"
                                      :agent-id "builtin/agent-rag-agent"
                                      :suite-file "x.edn"
                                      :fail-on-gate? true}})
              outputs (skills/get-result-outputs skill-result)]
          (is (skills/result-success? skill-result))
          (is (true? (:gate-failed? outputs)))
          (is (= summary (:summary outputs))))))))

(deftest other-exceptions-bubble
  (testing "Non-gate-failure exceptions from diagnostics propagate so the caller sees real failures"
    (with-redefs [diagnostics/agent-budget-benchmark
                  (fn [_target]
                    (throw (ex-info "typesense down" {:cause :network})))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (eval-delta/execute-eval-suite
                    {:inputs {:tenant "digdir"
                              :dataset-config-key "public-docs"
                              :agent-id "builtin/agent-rag-agent"
                              :suite-file "x.edn"}}))))))
