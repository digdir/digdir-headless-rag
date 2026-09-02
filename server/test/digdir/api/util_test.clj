(ns digdir.api.util-test
  "Tests for the skill-params merge layers in digdir.api.util.

   Each test pins one specific behaviour of the precedence chain
   (params > agent > config > defaults) so a regression in any layer
   is easy to localise."
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.api.util :as u]))

;; =============================================================================
;; Backwards-compat: existing 2-arity callers unchanged
;; =============================================================================

(deftest test-two-arity-backward-compatible
  (testing "Empty inputs produce the hardcoded defaults at known leaves"
    (let [out (u/build-rag-skill-params {} {})]
      (is (= 100 (get-in out [:builtin/retrieval :retrieve-top-k]))
          "retrieve-top-k defaults to 100")
      (is (= 10 (get-in out [:builtin/retrieval :max-per-document]))
          "max-per-document defaults to 10")
      (is (true? (get-in out [:builtin/retrieval :query-aware-boost]))
          "query-aware-boost defaults to true")))

  (testing "Params override config (existing behaviour)"
    (let [out (u/build-rag-skill-params
                {:retrieval-top-k 50}
                {:retrieve-top-k 200})]
      (is (= 200 (get-in out [:builtin/retrieval :retrieve-top-k])))))

  (testing "Config provides a value when params do not"
    (let [out (u/build-rag-skill-params
                {:retrieval-top-k 75 :rerank-top-k 30}
                {})]
      (is (= 75 (get-in out [:builtin/retrieval :retrieve-top-k])))
      (is (= 30 (get-in out [:builtin/rerank :top-k]))))))

;; =============================================================================
;; Precedence: params > agent > config > defaults
;; =============================================================================

(deftest test-precedence-params-over-agent
  (testing "Per-call params win when both params and agent set the same key"
    (let [config {:retrieval-top-k 50}
          agent {:builtin/retrieval {:retrieve-top-k 100}}
          params {:retrieve-top-k 200}
          out (u/build-rag-skill-params config params agent)]
      (is (= 200 (get-in out [:builtin/retrieval :retrieve-top-k]))
          "Params 200 wins over agent 100, config 50, default 100"))))

(deftest test-precedence-agent-over-config
  (testing "Agent skill-params win over config when params don't specify"
    (let [config {:retrieval-top-k 50}
          agent {:builtin/retrieval {:retrieve-top-k 100}}
          out (u/build-rag-skill-params config {} agent)]
      (is (= 100 (get-in out [:builtin/retrieval :retrieve-top-k]))
          "Agent 100 wins over config 50 (no per-call override)"))))

(deftest test-precedence-config-over-defaults
  (testing "Dataset config wins over hardcoded defaults when no agent or params"
    (let [config {:retrieval-top-k 75}
          out (u/build-rag-skill-params config {} {})]
      (is (= 75 (get-in out [:builtin/retrieval :retrieve-top-k]))
          "Config 75 wins over default 100"))))

(deftest test-precedence-defaults-when-nothing-set
  (testing "Hardcoded defaults apply when no layer provides the key"
    (let [out (u/build-rag-skill-params {} {} {})]
      (is (= 100 (get-in out [:builtin/retrieval :retrieve-top-k]))
          "Default 100 used when config/agent/params all silent")
      (is (= 10 (get-in out [:builtin/retrieval :max-per-document]))))))

;; =============================================================================
;; Partial overrides: agent and params can set DIFFERENT keys and both stick
;; =============================================================================

(deftest test-partial-overrides-stack
  (testing "Agent sets one key, params sets a different key — both survive"
    (let [agent {:builtin/retrieval {:strategy-weights {:content 0.0
                                                        :phrase 1.0
                                                        :metadata 0.0}}}
          params {:retrieve-top-k 200}
          out (u/build-rag-skill-params {} params agent)
          retr (get out :builtin/retrieval)]
      (is (= {:content 0.0 :phrase 1.0 :metadata 0.0}
             (:strategy-weights retr))
          "Agent's strategy-weights carries through")
      (is (= 200 (:retrieve-top-k retr))
          "Params' retrieve-top-k carries through")))

  (testing "Across skills: agent sets retrieval knob, params sets rerank knob"
    (let [agent {:builtin/retrieval {:retrieve-top-k 100}}
          params {:rerank-top-k 25}
          out (u/build-rag-skill-params {} params agent)]
      (is (= 100 (get-in out [:builtin/retrieval :retrieve-top-k])))
      (is (= 25 (get-in out [:builtin/rerank :top-k]))))))

;; =============================================================================
;; Replace semantics on inner maps (e.g. :strategy-weights)
;; =============================================================================

(deftest test-inner-maps-replace-not-merge
  (testing "Strategy-weights from a higher layer REPLACES the lower layer's value"
    (let [agent {:builtin/retrieval {:strategy-weights {:content 1.0
                                                        :phrase 0.5}}}
          params {:retrieve-strategy-weights {:phrase 0.8}}
          out (u/build-rag-skill-params {} params agent)]
      (is (= {:phrase 0.8}
             (get-in out [:builtin/retrieval :strategy-weights]))
          (str "Inner maps replace rather than deep-merge — partial weight "
               "overrides would be confusing, since a named tuning vector "
               "is a single semantic unit.")))))

;; =============================================================================
;; Boolean false semantics (compact-map preserves false, drops nil)
;; =============================================================================

(deftest test-false-survives-merge
  (testing "Params can explicitly set query-aware-boost=false, overriding default true"
    (let [params {:retrieve-query-aware-boost false}
          out (u/build-rag-skill-params {} params {})]
      (is (false? (get-in out [:builtin/retrieval :query-aware-boost]))
          (str "false from params must beat the hardcoded default true — "
               "params layer uses contains?-aware semantics for this key."))))

  (testing "Config can set query-aware-boost=false, overriding default true"
    (let [config {:retrieval-query-aware-boost false}
          out (u/build-rag-skill-params config {} {})]
      (is (false? (get-in out [:builtin/retrieval :query-aware-boost]))))))

;; =============================================================================
;; The shape of the output isn't disturbed by adding the agent layer
;; =============================================================================

(deftest test-output-keys-stable
  (testing "All four top-level skill keys are always present"
    (let [out (u/build-rag-skill-params {} {} {})]
      (is (contains? out :builtin/query-planner))
      (is (contains? out :builtin/retrieval))
      (is (contains? out :builtin/rerank))
      (is (contains? out :builtin/synthesis)))))

;; =============================================================================
;; End-to-end: production-winner agent setup
;; =============================================================================

(deftest test-production-winner-agent
  (testing "Round-5 winning skill-params on an agent reach the right leaves"
    (let [agent {:builtin/retrieval {:strategy-weights {:content 0.0
                                                        :phrase 1.0
                                                        :metadata 0.0}
                                     :strategy-contribution-caps {:phrase 5
                                                                  :content 0
                                                                  :metadata 0}
                                     :retrieve-top-k 100}
                 :builtin/rerank {:top-k 20}}
          out (u/build-rag-skill-params {} {} agent)
          retr (:builtin/retrieval out)
          rrank (:builtin/rerank out)]
      (is (= {:content 0.0 :phrase 1.0 :metadata 0.0}
             (:strategy-weights retr)))
      (is (= {:phrase 5 :content 0 :metadata 0}
             (:strategy-contribution-caps retr)))
      (is (= 100 (:retrieve-top-k retr)))
      (is (= 20 (:top-k rrank))))))
