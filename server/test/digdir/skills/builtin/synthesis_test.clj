(ns digdir.skills.builtin.synthesis-test
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.llm.client :as llm-client]
            [digdir.llm.openai :as llm]
            [digdir.skills.builtin.synthesis :as synthesis]))

(deftest test-detect-insufficient-context-english
  (testing "Detects common English insufficient-context phrasing"
    (let [detection (synthesis/detect-insufficient-context
                      "There is not enough information in the provided sources to answer this question.")]
      (is (:insufficient-context? detection))
      (is (= :not-enough-info (:signal detection))))))

(deftest test-detect-insufficient-context-norwegian
  (testing "Detects Norwegian insufficient-context phrasing"
    (let [detection (synthesis/detect-insufficient-context
                      "Jeg kan ikke svare sikkert fordi kildene mangler relevant informasjon.")]
      (is (:insufficient-context? detection))
      (is (= :kan-ikke-svare-fra-kilder (:signal detection))))))

(deftest test-detect-insufficient-context-negative
  (testing "Does not flag normal answers"
    (let [detection (synthesis/detect-insufficient-context
                      "Altinn Studio supports app development with role-based access and deployment pipelines.")]
      (is (false? (:insufficient-context? detection)))
      (is (nil? (:signal detection))))))

(deftest test-build-generation-prompt-includes-ambiguity-guidance
  (testing "Prompt includes general ambiguity-handling instructions"
    (let [prompt (synthesis/build-generation-prompt
                  nil
                  "[1] source"
                  "Hvor mange årsverk hadde Digdir i 2022?")]
      (is (re-find #"do not hide the ambiguity" prompt))
      (is (re-find #"Compare the user’s wording to the source labels" prompt))
      (is (re-find #"Do not merge or arithmetic-combine values" prompt))
      (is (re-find #"Every factual claim should have at least one citation" prompt)))))

(deftest test-build-generation-prompt-includes-language-rule
  (testing "Prompt tells the LLM to answer in the question's language to prevent code-switching"
    (let [prompt (synthesis/build-generation-prompt
                  nil
                  "[1] source"
                  "Når ble Altinn 3 lansert?")]
      (is (re-find #"SAME LANGUAGE" prompt))
      (is (re-find #"(?i)do not mix languages" prompt)))))

;; =============================================================================
;; Citation Validation Tests
;; =============================================================================

(deftest test-validate-citations-all-valid
  (testing "All citations reference valid source indices"
    (let [result (synthesis/validate-citations-programmatically
                   "According to [1] and [3], the answer is clear [2]."
                   {1 "chunk-a" 2 "chunk-b" 3 "chunk-c"})]
      (is (= #{1 2 3} (:valid-indices result)))
      (is (= #{} (:invalid-indices result)))
      (is (true? (:all-valid? result)))
      (is (= 3 (:total-references result))))))

(deftest test-validate-citations-some-invalid
  (testing "Some citations reference indices not in the citation index"
    (let [result (synthesis/validate-citations-programmatically
                   "See [1] and [5] for details."
                   {1 "chunk-a" 2 "chunk-b"})]
      (is (= #{1} (:valid-indices result)))
      (is (= #{5} (:invalid-indices result)))
      (is (false? (:all-valid? result)))
      (is (= 2 (:total-references result))))))

(deftest test-validate-citations-no-citations
  (testing "Response contains no citation references"
    (let [result (synthesis/validate-citations-programmatically
                   "This response has no citations at all."
                   {1 "chunk-a" 2 "chunk-b"})]
      (is (= #{} (:valid-indices result)))
      (is (= #{} (:invalid-indices result)))
      (is (true? (:all-valid? result)))
      (is (= 0 (:total-references result))))))

(deftest test-validate-citations-empty-citation-index
  (testing "Empty citation index makes all references invalid"
    (let [result (synthesis/validate-citations-programmatically
                   "Source [1] says so."
                   {})]
      (is (= #{} (:valid-indices result)))
      (is (= #{1} (:invalid-indices result)))
      (is (false? (:all-valid? result)))
      (is (= 1 (:total-references result))))))

(deftest test-validate-citations-duplicate-references
  (testing "Duplicate [N] references are counted once"
    (let [result (synthesis/validate-citations-programmatically
                   "As noted [1], confirmed [1], and also [2]."
                   {1 "chunk-a" 2 "chunk-b"})]
      (is (= #{1 2} (:valid-indices result)))
      (is (= #{} (:invalid-indices result)))
      (is (true? (:all-valid? result)))
      (is (= 2 (:total-references result))))))

(deftest test-validate-citations-nil-response
  (testing "Nil response text produces empty result"
    (let [result (synthesis/validate-citations-programmatically
                   nil
                   {1 "chunk-a"})]
      (is (= #{} (:valid-indices result)))
      (is (= #{} (:invalid-indices result)))
      (is (true? (:all-valid? result)))
      (is (= 0 (:total-references result))))))

(deftest test-parse-citations-returns-sorted-distinct-citations
  (testing "Parsed citations are distinct and sorted by index"
    (let [result (synthesis/parse-citations
                  "Svar [3], [1], [3], [2]."
                  {1 "c1" 2 "c2" 3 "c3"})]
      (is (= [{:index 1 :chunk-id "c1"}
              {:index 2 :chunk-id "c2"}
              {:index 3 :chunk-id "c3"}]
             result)))))

(deftest test-renumber-citations-sequentializes-valid-citations
  (testing "Renumbers parsed citations to a compact sequence while preserving mapping"
    (let [result (synthesis/renumber-citations
                   "Svar [2] og [4]."
                   [{:index 2 :chunk-id "c2"}
                    {:index 4 :chunk-id "c4"}]
                   {2 "c2" 4 "c4"})]
      (is (= "Svar [1] og [2]." (:response result)))
      (is (= [{:index 1 :chunk-id "c2"}
              {:index 2 :chunk-id "c4"}]
             (:citations result)))
      (is (= {1 "c2" 2 "c4"} (:citation-index result))))))

(deftest test-execute-synthesis-validates-renumbered-citations
  (testing "Citation validation reflects the final renumbered response rather than stale pre-renumber indices"
    ;; `execute-synthesis` calls `digdir.llm.client/create-chat-completion`,
    ;; which only delegates to wkok on the `:impl :azure` path — the non-Azure
    ;; default is a direct clj-http POST. Stubbing wkok here therefore
    ;; intercepted nothing and the test issued a real request.
    (with-redefs [llm/use-azure-openai (fn [_] false)
                  llm-client/create-chat-completion
                  (fn [& _]
                    {:choices [{:message {:content "Svar [3]."}}]})]
      (let [result (synthesis/execute-synthesis
                    {:inputs {:query "Når ble Altinn 3 lansert?"
                              :context-docs [{:page_content "irrelevant" :metadata {:source "c1"}}
                                             {:page_content "irrelevant" :metadata {:source "c2"}}
                                             {:page_content "launch info" :metadata {:source "c3"}}]}
                     :parameters {:model "test-model"}
                     :services {}})
            outputs (:outputs result)]
        (is (= "Svar [1]." (:response outputs)))
        (is (= {1 "c3"} (:citation-index outputs)))
        (is (= #{1} (get-in outputs [:citation-validation :valid-indices])))
        (is (= #{} (get-in outputs [:citation-validation :invalid-indices])))
        (is (= 1 (get-in outputs [:citation-validation :total-references])))))))

(deftest citation-validation-reports-groundedness-not-just-integrity
  ;; `:all-valid?` answers "are the [N] markers the model emitted real?" — it is
  ;; an INTEGRITY check. It says nothing about whether the answer cited anything
  ;; at all, and an answer with no citations has no INVALID ones, so it passes
  ;; trivially with `:all-valid? true, :total-references 0`.
  ;;
  ;; That is the whole gap behind the observed behaviour: asked "What is the
  ;; capital of France?" against a Norwegian public-sector corpus, the agent
  ;; searched, retrieved 8-11 chunks, found nothing relevant, and answered
  ;; "Paris" from model knowledge with zero citations — reproduced 5/5, and on
  ;; BOTH gpt-4o and gpt-5.6-sol, and on the pre-PR code, so it is neither a
  ;; model trait nor a regression. Nothing downstream could distinguish that
  ;; from a well-grounded answer, because the only signal available said valid.
  ;;
  ;; `:grounded?` is that missing distinction: did at least one citation both
  ;; get emitted AND resolve to a real chunk.
  (testing "an uncited answer is valid-but-ungrounded"
    (let [v (synthesis/validate-citations-programmatically
              "The capital of France is Paris." {1 "chunk-a" 2 "chunk-b"})]
      (is (true? (:all-valid? v))
          "integrity holds — there are no invalid references to find")
      (is (zero? (:total-references v)))
      (is (false? (:grounded? v))
          "but nothing was cited, so the answer is not grounded in the corpus")))

  (testing "an answer citing a real chunk is grounded"
    (let [v (synthesis/validate-citations-programmatically
              "Altinn Events lets you subscribe to events [1]." {1 "chunk-a"})]
      (is (true? (:all-valid? v)))
      (is (true? (:grounded? v)))))

  (testing "an answer citing only a NON-existent chunk is not grounded"
    ;; Integrity already fails here; groundedness must fail too, rather than
    ;; counting a fabricated marker as evidence.
    (let [v (synthesis/validate-citations-programmatically
              "Altinn does this [7]." {1 "chunk-a"})]
      (is (false? (:all-valid? v)))
      (is (false? (:grounded? v))))))
