(ns digdir.llm.provider-capture-test
  "the resolved-parameter comparison, as a test.

   `test/fixtures/provider-capture/provider-capture.edn` is the before-capture: every call
   the corpus makes across the configuration matrix, taken on Phase 0 — where
   it went, what it sent, which runner layer set each parameter, and the joined
   source of :model, :temperature and :max-tokens (`digdir.llm.provider-harness`).

   Phases 1-4 must leave it unchanged except where a phase INTENDS a change and
   says which. A phase declares that in `declared-changes` below, in the same
   diff, so a reviewer sees the intended changes next to the code that makes
   them. Anything else that moved is a regression — and this is the only check
   in the mission that distinguishes \"same behaviour\" from \"same test outcome\".

   The differential half is paired with an absolute half, because a capture
   that silently recorded nothing would diff clean against another empty one."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.llm.provider-harness :as h]))

(def ^:private before-capture-commit
  "The commit the before-capture describes: public/main after the OpenRouter key-leak fix (on the step-parameter provenance record
   Phase 0 and the provider-selector pins), before Phase 1. The OpenRouter key-leak fix changes nothing the capture
   sees (measured: zero record changes against the 18ba2502 capture)."
  "ece6ce65b321c4ec10fe08c9844bf872f481ffad")

(def ^:private before-capture-records-sha256
  "Structural hash of the committed before-capture's records
   (`h/records-hash`). Hand-typed ON PURPOSE: regenerating the fixture changes
   it, so a regeneration cannot pass without an edit here that a reviewer sees.
   Changing this value after Phase 1 is exactly the act that destroys the
   evidence — see `h/regeneration-warning`."
  "6c8b37150575ff4f597bc5e30552ee7fbe455ea3f5e604bc70b443ac30c06e6a")

(def ^:private declared-changes
  "What the CURRENT phase intends to change relative to the committed
   before-capture — rules and refusals, in the shape documented at
   `digdir.llm.provider-harness` \"Declared changes\". Declared next to the
   code that makes the change, in the same diff. Phase 1 declares nothing."
  {:rules [] :refusals []})

(def ^:private taken (delay (h/capture declared-changes)))

(deftest the-before-capture-is-the-one-taken-before-phase-1
  (testing (str "The before-half is only evidence while it describes the world BEFORE
                 Phase 1. " h/regeneration-warning)
    (let [snap (h/read-snapshot)]
      (is (str/includes? (str (:taken-on snap)) before-capture-commit)
          "captured on the pinned commit")
      (is (= before-capture-records-sha256 (h/records-hash (:records snap)))
          (str "the before-capture's CONTENT changed. If it was regenerated, revert it "
               "and declare the change in `declared-changes` instead.")))))

(deftest the-shell-cannot-leak-into-the-capture
  (testing "Transport env reads the stubs cannot neutralise. If any is set, the
            capture would describe the shell that took it, not the code."
    (is (empty? (filterv #(some? (System/getenv %)) h/env-that-would-leak-in))
        "unset these and re-run")))

(deftest every-run-is-a-trustworthy-record
  (let [{:keys [runs unregistered problems records]} @taken
        expected (h/runs)]
    (is (empty? unregistered)
        (str "corpus skills not registered — other tests clear the registry: " unregistered))
    (is (= (count expected) runs) "every planned run executed")
    (is (<= 150 runs) "the matrix is the size it claims — an empty corpus would pass everything below")
    (is (= runs (count records)))
    (is (empty? problems)
        (str "runs that cannot be trusted as records (threw, made other than one call, "
             "or the two instruments disagreed): " (pr-str problems)))
    (testing "every entry and every arm is present"
      (is (= (set (map (fn [[e arm]] [(:id e) arm]) expected))
             (set (map (juxt :entry :arm) records)))))
    (testing "the joins produced labels, not holes"
      (is (every? #(every? keyword? (vals (:source %))) records)))))

(deftest the-capture-matches-the-committed-before-capture
  (let [snap (h/read-snapshot)]
    (is (some? snap) (str "the before-capture must be committed at " h/snapshot-path))
    (is (<= 150 (count (:records snap))) "and must itself be non-empty")
    (let [{:keys [records problems]} (h/expected-after (:records snap) declared-changes)]
      (is (empty? problems)
          (str "a declaration must select something and describe the before-state truly: " (pr-str problems)))
      (is (= {:removed [] :added [] :changed {}} (h/diff-records records (:records @taken)))
          "the capture must equal the before-capture with the declared changes applied — nothing more, nothing less"))))

(deftest declarations-are-checked-not-trusted
  (testing "The machinery, on records whose answer is known — both directions."
    (let [before [{:entry :e :arm [:a :none] :path [:g :s] :call-index 0 :sent {:key-from :secret :branch :openai}}
                  {:entry :e :arm [:b :none] :path [:g :s] :call-index 0 :sent {:key-from :opts :branch :azure}}]
          rule {:why "t" :select {[:sent :branch] :openai} :from {[:sent :key-from] :secret} :to {[:sent :key-from] :opts}}]
      (testing "a true rule rewrites exactly what it selects"
        (let [{:keys [records problems]} (h/expected-after before {:rules [rule]})]
          (is (empty? problems))
          (is (= [:opts :opts] (mapv #(get-in % [:sent :key-from]) records)))))
      (testing "a rule that selects nothing is a failure, not a no-op"
        (is (= [[:vacuous-rule "v"]]
               (:problems (h/expected-after before {:rules [(assoc rule :why "v" :select {[:sent :branch] :none})]})))))
      (testing "a rule whose :from misdescribes the before-state is a failure"
        (is (= :rule-from-mismatch
               (ffirst (:problems (h/expected-after before {:rules [(assoc rule :from {[:sent :key-from] :env})]}))))))
      (testing "a refusal replaces the runs it selects, and a vacuous one fails"
        (let [{:keys [records problems]} (h/expected-after before {:refusals [{:why "r" :select {[:arm] #{[:a :none]}} :names-path "p"}]})]
          (is (empty? problems))
          (is (some #(= {:entry :e :arm [:a :none] :refused "p"} %) records))
          (is (= 2 (count records))))
        (is (= [[:vacuous-refusal "r"]]
               (:problems (h/expected-after before {:refusals [{:why "r" :select {[:arm] #{[:zz :none]}} :names-path "p"}]}))))))))
