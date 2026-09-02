(ns digdir.demo.self-improve-facts-graph-test
  "Phase D2 — coverage for :docs/self-improve-facts-graph and
   its inner sub-graph. Mirrors self-improve-phrases-graph-test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.demo.self-improve-facts-graph :as sifg]
            [digdir.skills.enrichment.analyze-corpus :as enrichment-analyze]
            [digdir.skills.enrichment.apply-facts :as enrichment-apply]
            [digdir.skills.enrichment.compose-report :as enrichment-compose]
            [digdir.skills.enrichment.eval-delta :as enrichment-eval]
            [digdir.skills.enrichment.extract-intent :as enrichment-intent]
            [digdir.skills.enrichment.fetch-chunk-context :as enrichment-fetch]
            [digdir.skills.enrichment.mark-keep :as enrichment-keep]
            [digdir.skills.enrichment.propose-facts :as enrichment-propose]
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
    (sifg/register!)
    (t)))

(deftest sub-graph-registered
  (testing ":docs/enrich-one-chunk-facts is in the skill-graph registry"
    (is (some? (templates/get-skill-graph :docs/enrich-one-chunk-facts)))))

(deftest sub-graph-passes-full-validation
  (testing "Schema + semantic validation pass on the per-chunk facts sub-graph"
    (is (some? (graph-schema/fully-validate-graph! sifg/enrich-one-chunk-facts-graph)))))

(deftest all-required-skills-are-registered
  (testing "Every skill the facts sub-graph dispatches to is in the registry"
    (is (sifg/all-required-skills-present?))))

(deftest sub-graph-uses-propose-and-apply-facts
  (testing "propose + apply steps target the facts-specific skills"
    (let [propose (->> sifg/enrich-one-chunk-facts-graph :steps
                       (filter #(= :propose (:id %))) first)
          apply' (->> sifg/enrich-one-chunk-facts-graph :steps
                      (filter #(= :apply (:id %))) first)]
      (is (= :builtin/enrichment-propose-facts (:skill propose)))
      (is (= :builtin/enrichment-apply-facts (:skill apply'))))))

(deftest outer-graph-registered
  (testing ":docs/self-improve-facts-graph is in the skill-graph registry"
    (is (some? (templates/get-skill-graph :docs/self-improve-facts-graph)))))

(deftest outer-graph-passes-full-validation
  (testing "Schema + semantic validation pass on the outer facts graph"
    (is (some? (graph-schema/fully-validate-graph! sifg/self-improve-facts-graph)))))

(deftest outer-graph-analyze-targets-fact-assertions
  (testing "analyze step's :enrichment-type parameter is :fact-assertions"
    (let [analyze (->> sifg/self-improve-facts-graph :steps
                       (filter #(= :analyze (:id %))) first)]
      (is (= :fact-assertions (-> analyze :parameters :enrichment-type))))))

(deftest outer-graph-foreach-uses-facts-sub-graph
  (testing "foreach dispatches into the fact-assertions sub-graph"
    (let [step (->> sifg/self-improve-facts-graph :steps
                    (filter #(= :per-chunk (:id %))) first)]
      (is (= :docs/enrich-one-chunk-facts
             (-> step :do :sub-graph :graph-id))))))

(deftest sub-graph-has-verify-step-and-composite-gate
  (testing "D2.8 verify wiring: verify step pins :fact-assertions, decide selects on [:verify :keep?]"
    (let [steps (:steps sifg/enrich-one-chunk-facts-graph)
          verify (->> steps (filter #(= :verify (:id %))) first)
          decide (->> steps (filter #(= :decide (:id %))) first)]
      (is (some? verify) "An explicit :verify step exists")
      (is (= :builtin/enrichment-verify-retrieval (:skill verify)))
      (is (= :fact-assertions (-> verify :parameters :enrichment-type))
          "Enrichment-type is a parameter literal (not an input — keyword in :inputs would be a step-ref)")
      (is (= [:verify :keep?] (-> decide :select :on))
          "Decide composes verify+eval via :verify :keep?"))))

