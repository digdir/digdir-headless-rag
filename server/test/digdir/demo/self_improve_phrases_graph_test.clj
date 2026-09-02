(ns digdir.demo.self-improve-phrases-graph-test
  "Phase D1 — coverage for :docs/self-improve-phrases-graph and
   its inner sub-graph. Mirrors self-improve-graph-test (the
   hypothetical-questions variant)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.demo.self-improve-phrases-graph :as sipg]
            [digdir.skills.enrichment.analyze-corpus :as enrichment-analyze]
            [digdir.skills.enrichment.apply-phrases :as enrichment-apply]
            [digdir.skills.enrichment.compose-report :as enrichment-compose]
            [digdir.skills.enrichment.eval-delta :as enrichment-eval]
            [digdir.skills.enrichment.extract-intent :as enrichment-intent]
            [digdir.skills.enrichment.fetch-chunk-context :as enrichment-fetch]
            [digdir.skills.enrichment.mark-keep :as enrichment-keep]
            [digdir.skills.enrichment.propose-phrases :as enrichment-propose]
            [digdir.skills.enrichment.revert-chunk :as enrichment-revert]
            [digdir.skills.enrichment.verify-retrieval :as enrichment-verify]
            [digdir.skills.graph.schema :as graph-schema]
            [digdir.skills.templates.core :as templates]))

(use-fixtures :once
  (fn [t]
    ;; Re-register enrichment skills so a prior test that called
    ;; skills-api/reset-skills! doesn't leave the registry empty.
    (enrichment-analyze/register!)
    (enrichment-apply/register!)
    (enrichment-compose/register!)
    (enrichment-eval/register!)
    (enrichment-intent/register!)
    (enrichment-fetch/register!)
    (enrichment-keep/register!)
    (enrichment-propose/register!)
    (enrichment-revert/register!)
    (enrichment-verify/register!)
    (sipg/register!)
    (t)))

(deftest sub-graph-registered
  (testing ":docs/enrich-one-chunk-phrases is in the skill-graph registry"
    (is (some? (templates/get-skill-graph :docs/enrich-one-chunk-phrases)))))

(deftest sub-graph-passes-full-validation
  (testing "Schema + semantic validation pass on the per-chunk phrases sub-graph"
    (is (some? (graph-schema/fully-validate-graph! sipg/enrich-one-chunk-phrases-graph)))))

(deftest all-required-skills-are-registered
  (testing "Every skill the phrases sub-graph dispatches to is in the registry"
    (is (sipg/all-required-skills-present?))))

(deftest sub-graph-uses-propose-and-apply-phrases
  (testing "propose + apply steps target the phrases-specific skills, not the questions ones"
    (let [propose (->> sipg/enrich-one-chunk-phrases-graph :steps
                       (filter #(= :propose (:id %))) first)
          apply' (->> sipg/enrich-one-chunk-phrases-graph :steps
                      (filter #(= :apply (:id %))) first)]
      (is (= :builtin/enrichment-propose-phrases (:skill propose)))
      (is (= :builtin/enrichment-apply-phrases (:skill apply'))))))

(deftest outer-graph-registered
  (testing ":docs/self-improve-phrases-graph is in the skill-graph registry"
    (is (some? (templates/get-skill-graph :docs/self-improve-phrases-graph)))))

(deftest outer-graph-passes-full-validation
  (testing "Schema + semantic validation pass on the outer phrases graph"
    (is (some? (graph-schema/fully-validate-graph! sipg/self-improve-phrases-graph)))))

(deftest outer-graph-analyze-targets-verified-phrases
  (testing "analyze step's :enrichment-type parameter is :verified-phrases"
    (let [analyze (->> sipg/self-improve-phrases-graph :steps
                       (filter #(= :analyze (:id %))) first)]
      (is (= :verified-phrases (-> analyze :parameters :enrichment-type))))))

(deftest outer-graph-foreach-uses-phrases-sub-graph
  (testing "foreach dispatches into the verified-phrases sub-graph (not the questions one)"
    (let [step (->> sipg/self-improve-phrases-graph :steps
                    (filter #(= :per-chunk (:id %))) first)]
      (is (= :docs/enrich-one-chunk-phrases
             (-> step :do :sub-graph :graph-id))))))

(deftest sub-graph-has-verify-step-and-composite-gate
  (testing "D2.8 verify wiring: verify step pins :verified-phrases, decide selects on [:verify :keep?]"
    (let [steps (:steps sipg/enrich-one-chunk-phrases-graph)
          verify (->> steps (filter #(= :verify (:id %))) first)
          decide (->> steps (filter #(= :decide (:id %))) first)]
      (is (some? verify))
      (is (= :builtin/enrichment-verify-retrieval (:skill verify)))
      (is (= :verified-phrases (-> verify :parameters :enrichment-type)))
      (is (= [:verify :keep?] (-> decide :select :on))))))

