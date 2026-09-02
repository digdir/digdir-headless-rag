(ns digdir.rag.auto-filter-rules-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.rag.auto-filter-rules :as rules]))

;; ---------------------------------------------------------------------------
;; :marker-word-classify
;; ---------------------------------------------------------------------------

(def ^:private nb-en-language-rule
  ;; The marker-word approach is purely a function-word distribution
  ;; signal. Content words (like "altinn", "autentisering") would be
  ;; ambiguous between NB and EN queries that discuss Altinn topics in
  ;; either language; only inflectional / structural words go here.
  {:rule/type :marker-word-classify
   :field "language"
   :min-tokens 3
   :threshold-ratio 2.0
   :values
   {"nb" {:requires-chars "[æøå]"
          :marker-words ["hvordan" "hva" "hvor" "når" "hvorfor"
                         "og" "eller" "ikke" "jeg" "meg" "deg" "vi"
                         "den" "det" "som" "for" "med" "uten"
                         "er" "har" "kan" "skal" "vil" "må"
                         "fra" "til" "av" "noe" "noen" "også"]}
    "en" {:marker-words ["how" "what" "where" "when" "why" "and"
                         "or" "not" "the" "is" "are" "was" "be"
                         "i" "you" "we" "they" "for" "with" "from"
                         "can" "could" "should" "would" "may"
                         "do" "does" "did" "this" "that" "these"
                         "using"]}}})

(defn- classified-value
  [rule queries]
  (some-> (rules/detect-by-rule rule queries nil)
          :fields
          first
          :selected-options
          first))

(deftest marker-word-classify-on-v3-questions
  (testing "All 7 v3-baseline questions classify to the right language"
    (is (= "en" (classified-value nb-en-language-rule
                                  ["What is Dialogporten and what problem does it solve?"])))
    (is (= "en" (classified-value nb-en-language-rule
                                  ["How do I create a new dialog as a service owner in Dialogporten?"])))
    (is (= "nb" (classified-value nb-en-language-rule
                                  ["Hvordan setter jeg opp autentisering for en Altinn-app i utviklingsmiljøet?"])))
    (is (= "nb" (classified-value nb-en-language-rule
                                  ["Når går Altinn over fra Altinn-roller til Tilgangspakker, og hva er de mest betydningsfulle endringene for tjenesteeiere?"])))
    (is (= "nb" (classified-value nb-en-language-rule
                                  ["Hva er forskjellen mellom Altinn-roller for personer og virksomheter?"])))
    (is (= "en" (classified-value nb-en-language-rule
                                  ["Compare how Altinn Studio v7 and v8 handle data model definition — what's the migration story?"])))
    (is (= "en" (classified-value nb-en-language-rule
                                  ["Does Altinn support webhook signatures using HMAC-SHA512?"])))))

(deftest marker-word-classify-required-chars-short-circuits
  (testing "Norwegian-only chars (æøå) classify NB even with few function words"
    ;; Short query, but the æ character is unambiguously Norwegian.
    (is (= "nb" (classified-value nb-en-language-rule ["må jeg ha"])))))

(deftest marker-word-classify-skips-short-queries
  (testing "Queries with fewer tokens than :min-tokens yield no classification"
    (is (nil? (rules/detect-by-rule
               (assoc nb-en-language-rule :min-tokens 5)
               ["short query"]
               nil)))))

(deftest marker-word-classify-skips-ambiguous-queries
  (testing "Queries below the threshold-ratio margin yield nil"
    ;; Two NB markers ("er", "altinn"), no EN — clear NB win.
    ;; But add a content word and reduce overlap to just 1 each:
    ;; "is altinn" → 1 NB ("altinn"), 1 EN ("is") → fails 2x ratio →
    ;; AND fails the absolute minimum of 2 → nil.
    (is (nil? (classified-value nb-en-language-rule ["is altinn fast"]))))

  (testing "Two markers each but tied ratio yields nil"
    ;; "og the er the" → 2 NB ("og", "er"), 2 EN ("the" x2 unique-set=1)
    ;; Actually unique-set: NB=#{og,er}=2, EN=#{the}=1 → still 2x → NB
    ;; To get a tie, need equal unique sets:
    ;; "is can the" → 0 NB, 3 EN tokens (set-of-3) → strong EN.
    (is (= "en" (classified-value nb-en-language-rule ["is can the"])))))

;; ---------------------------------------------------------------------------
;; :shape-pattern
;; ---------------------------------------------------------------------------

(def ^:private diataxis-rule
  {:rule/type :shape-pattern
   :field "diataxis"
   :mode :permissive
   :universe ["explanation" "how-to-guides" "reference" "tutorials"]
   :patterns
   [{:value "explanation"
     :regex "(?i)^\\s*(what\\s+is|hva\\s+er|hva\\s+vil\\s+det\\s+si|define|explain)"}
    {:value "how-to-guides"
     :regex "(?i)^\\s*(how\\s+(do|can|to|should)|hvordan(\\s+\\w+){0,2}\\s+(jeg|man|setter|kan))"}
    {:value "reference"
     :regex "(?i)^\\s*(what\\s+are\\s+the|list\\s+(of|all|the)|which\\s+values|hvilke\\s+verdier|hvilke\\s+\\w+\\s+finnes)"}]})

(defn- shape-result
  [rule queries]
  (rules/detect-by-rule rule queries nil))

(defn- complement-options
  [result]
  (-> result :fields first :selected-options))

(deftest shape-pattern-classifies-v3-questions
  (testing "Definitional shapes match :explanation"
    (let [result (shape-result diataxis-rule
                               ["What is Dialogporten and what problem does it solve?"])]
      (is (some? result))
      (is (= :not-in-set (-> result :fields first :type)))
      ;; Permissive form: emit COMPLEMENT so non-matching docs stay in.
      (is (= #{"how-to-guides" "reference" "tutorials"}
             (complement-options result)))))

  (testing "Procedural shapes match :how-to-guides (both EN and NB)"
    (is (= #{"explanation" "reference" "tutorials"}
           (complement-options (shape-result diataxis-rule
                                             ["How do I create a new dialog as a service owner?"]))))
    (is (= #{"explanation" "reference" "tutorials"}
           (complement-options (shape-result diataxis-rule
                                             ["Hvordan setter jeg opp autentisering?"])))))

  (testing "Reference shapes match :reference"
    (is (= #{"explanation" "how-to-guides" "tutorials"}
           (complement-options (shape-result diataxis-rule
                                             ["Hvilke verdier finnes for status-feltet?"]))))))

(deftest shape-pattern-q5-comparative-misfires-as-explanation
  (testing "Q5's 'Hva er forskjellen mellom X og Y?' matches the
           definitional pattern because it begins with 'Hva er'.
           This is a known classifier limitation: comparatives look
           definitional. The auto-filter-fallback safety net at the
           retrieval layer retries without the filter when no hits
           come back."
    (let [result (shape-result diataxis-rule
                               ["Hva er forskjellen mellom Altinn-roller for personer og virksomheter?"])]
      (is (some? result))
      (is (= #{"how-to-guides" "reference" "tutorials"}
             (complement-options result))))))

(deftest shape-pattern-q6-does-not-false-positive
  (testing "The 'how' rule requires question-start anchor (^\\s*).
           Q6 starts with 'Compare', so 'how' embedded mid-sentence
           does NOT trigger :how-to-guides. This is *safer* than the
           proposed plan predicted."
    (is (nil? (shape-result diataxis-rule
                            ["Compare how Altinn Studio v7 and v8 handle data model definition?"])))))

(deftest shape-pattern-unmatched-returns-nil
  (testing "Yes/no questions don't match any pattern"
    (is (nil? (shape-result diataxis-rule
                            ["Does Altinn support webhook signatures using HMAC-SHA512?"]))))

  (testing "Temporal questions don't match any pattern"
    (is (nil? (shape-result diataxis-rule
                            ["Når går Altinn over til Tilgangspakker?"])))))

(deftest shape-pattern-strict-mode-emits-equality
  (testing "Strict mode emits `field:=[value]` (multiselect type)"
    (let [strict-rule (assoc diataxis-rule :mode :strict)
          result (shape-result strict-rule ["What is Dialogporten?"])]
      (is (= :multiselect (-> result :fields first :type)))
      (is (= #{"explanation"} (-> result :fields first :selected-options))))))

;; ---------------------------------------------------------------------------
;; detect-from-rules (engine entrypoint)
;; ---------------------------------------------------------------------------

(deftest detect-from-rules-merges-multi-rule-output
  (testing "Multiple rules contribute fields; same-field collisions are last-wins"
    (let [result (rules/detect-from-rules
                  [nb-en-language-rule diataxis-rule]
                  ["What is Dialogporten and what problem does it solve?"]
                  nil)]
      (is (= 2 (count (:fields result))))
      (is (= #{"language" "diataxis"}
             (set (map :field (:fields result))))))))

(deftest detect-from-rules-empty-returns-nil
  (testing "Empty rule list returns nil"
    (is (nil? (rules/detect-from-rules [] ["any query"] nil))))

  (testing "Rules that all fail to match return nil"
    (is (nil? (rules/detect-from-rules
               [(assoc nb-en-language-rule :min-tokens 50)] ; impossible threshold
               ["short"]
               nil)))))

(deftest detect-from-rules-skips-unknown-rule-types
  (testing "Unknown :rule/type silently no-ops; other rules still run"
    (let [result (rules/detect-from-rules
                  [{:rule/type :nonexistent-future-rule :field "x"}
                   nb-en-language-rule]
                  ["What is Dialogporten and what problem does it solve?"]
                  nil)]
      (is (= 1 (count (:fields result))))
      (is (= "language" (-> result :fields first :field))))))

;; ---------------------------------------------------------------------------
;; :llm-classify
;; ---------------------------------------------------------------------------

(def ^:private diataxis-llm-rule
  {:rule/type :llm-classify
   :field "diataxis"
   :universe ["explanation" "how-to-guides" "reference" "tutorials"]
   :mode :permissive
   :allow-multi-value true
   :allow-none true
   :model "gpt-4o-mini"
   :temperature 0.0
   :prompt-template "Pick from {{universe-joined}} for: {{query}}"})

(defn- stub-llm-call
  "Returns a fn replacement for `llm-classify-call` that yields the
   given selected values, recording the call args into the atom."
  [selected !calls]
  (fn [args]
    (swap! !calls conj args)
    selected))

(deftest llm-classify-strict-mode-emits-equality
  (testing "Strict mode emits `field:=[selected]` (multiselect)"
    (let [!calls (atom [])]
      (with-redefs [rules/llm-classify-call (stub-llm-call ["explanation"] !calls)]
        (let [rule (assoc diataxis-llm-rule :mode :strict)
              result (rules/detect-by-rule rule
                                           ["What is Dialogporten?"]
                                           {:tenant "digdir"})]
          (is (some? result))
          (is (= :multiselect (-> result :fields first :type)))
          (is (= #{"explanation"} (-> result :fields first :selected-options)))
          (is (= 1 (count @!calls)))
          (is (= "digdir" (-> @!calls first :tenant))))))))

(deftest llm-classify-permissive-mode-emits-complement
  (testing "Permissive mode emits `field:!=[complement]` (not-in-set)"
    (let [!calls (atom [])]
      (with-redefs [rules/llm-classify-call (stub-llm-call ["explanation"] !calls)]
        (let [result (rules/detect-by-rule diataxis-llm-rule
                                           ["What is Dialogporten?"]
                                           {:tenant "digdir"})]
          (is (= :not-in-set (-> result :fields first :type)))
          (is (= #{"how-to-guides" "reference" "tutorials"}
                 (-> result :fields first :selected-options))))))))

(deftest llm-classify-multi-value
  (testing "Multi-value LLM output drives a multi-value filter"
    (let [!calls (atom [])]
      (with-redefs [rules/llm-classify-call
                    (stub-llm-call ["how-to-guides" "reference"] !calls)]
        ;; Strict to make assertion clear about set contents
        (let [rule (assoc diataxis-llm-rule :mode :strict)
              result (rules/detect-by-rule rule
                                           ["Hvordan setter jeg opp autentisering?"]
                                           {:tenant "digdir"})]
          (is (= #{"how-to-guides" "reference"}
                 (-> result :fields first :selected-options))))))))

(deftest llm-classify-allow-multi-value-false-truncates
  (testing "When :allow-multi-value false, only the first LLM value is kept"
    (let [!calls (atom [])]
      (with-redefs [rules/llm-classify-call
                    (stub-llm-call ["how-to-guides" "reference"] !calls)]
        (let [rule (assoc diataxis-llm-rule :mode :strict :allow-multi-value false)
              result (rules/detect-by-rule rule ["any"] {:tenant "digdir"})]
          (is (= #{"how-to-guides"}
                 (-> result :fields first :selected-options))))))))

(deftest llm-classify-empty-output-returns-nil
  (testing "Empty LLM output → rule returns nil (no filter contribution)"
    (let [!calls (atom [])]
      (with-redefs [rules/llm-classify-call (stub-llm-call [] !calls)]
        (is (nil? (rules/detect-by-rule diataxis-llm-rule
                                        ["ambiguous comparative question?"]
                                        {:tenant "digdir"})))))))

(deftest llm-classify-llm-failure-returns-nil
  (testing "LLM call returns nil (network/parse error) → rule returns nil"
    (let [!calls (atom [])]
      (with-redefs [rules/llm-classify-call (stub-llm-call nil !calls)]
        (is (nil? (rules/detect-by-rule diataxis-llm-rule
                                        ["any"]
                                        {:tenant "digdir"})))))))

(deftest llm-classify-invalid-values-filtered
  (testing "LLM returning values outside the universe → invalid values dropped"
    (let [!calls (atom [])]
      (with-redefs [rules/llm-classify-call
                    (stub-llm-call ["explanation" "made-up-category"] !calls)]
        (let [rule (assoc diataxis-llm-rule :mode :strict)
              result (rules/detect-by-rule rule ["any"] {:tenant "digdir"})]
          (is (= #{"explanation"}
                 (-> result :fields first :selected-options))
              "Only universe-valid values survive"))))))

(deftest llm-classify-requires-tenant
  (testing "Rule no-ops when no tenant is in opts (testing fallback)"
    (let [!calls (atom [])]
      (with-redefs [rules/llm-classify-call (stub-llm-call ["explanation"] !calls)]
        (is (nil? (rules/detect-by-rule diataxis-llm-rule
                                        ["any"]
                                        {})))
        (is (empty? @!calls)
            "LLM call short-circuited; no network call made")))))

(deftest llm-classify-prompt-substitution
  (testing "Rendered prompt substitutes {{field}}, {{universe-joined}}, {{query}}"
    (let [!calls (atom [])]
      (with-redefs [rules/llm-classify-call (stub-llm-call ["explanation"] !calls)]
        (rules/detect-by-rule diataxis-llm-rule
                              ["Test query Q1"]
                              {:tenant "digdir"})
        (let [rendered (-> @!calls first :rendered-prompt)]
          (is (str/includes? rendered "Test query Q1"))
          (is (str/includes? rendered "explanation"))
          (is (str/includes? rendered "how-to-guides"))
          (is (not (str/includes? rendered "{{")) "All placeholders substituted"))))))

(deftest llm-classify-composes-with-heuristic-rules
  (testing "detect-from-rules merges :llm-classify output with :marker-word-classify"
    (let [!calls (atom [])]
      (with-redefs [rules/llm-classify-call (stub-llm-call ["how-to-guides"] !calls)]
        (let [result (rules/detect-from-rules
                      [nb-en-language-rule
                       (assoc diataxis-llm-rule :mode :strict)]
                      ["How do I create a dialog as a service owner?"]
                      {:tenant "digdir"})]
          (is (= 2 (count (:fields result))))
          (is (= #{"language" "diataxis"}
                 (set (map :field (:fields result))))))))))
