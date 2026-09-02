(ns digdir.skills.builtin.query-planner-test
  "Unit tests for the intent-aware query-planner skill.

   The planner runs a single LLM call and emits both:
   - :user-intent — canonical clean topical question in corpus language
   - :queries — vec with :user-intent prepended to N expansion phrases

   Tests stub `digdir.llm.client/create-chat-completion` — the real HTTP
   boundary — to pin behavior without hitting a provider. (They stubbed
   `litellm/completion` until 6d53918 migrated the planner off litellm-azure;
   the stub then silently stopped intercepting and this whole namespace went
   red. See issue #32.) Mock responses use OpenAI's `:tool_calls` spelling so
   `planner-completion`'s `:tool_calls` -> `:tool-calls` normalization is
   exercised rather than bypassed. The planner's prompt design and tool-call output shape
   were rewritten in slice 18 (see
   plans/in-progress/target-optimal-baseline-v3/18-intent-aware-query-planner.md);
   inspired by `digdir.skills.enrichment.extract-intent` in src-dev/."
  (:require [digdir.test-utils :as tu]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [digdir.config.accessor :as cfg]
            [digdir.rag.retrieval :as retrieval]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.builtin.query-planner :as query-planner]
            [digdir.llm.client :as client]))

(defn- stub-config [_ & _] nil)

(defn- mock-chat-completion
  "Build a stub `client/create-chat-completion` returning a single tool-call
   shaped as the new planner expects (user_intent + search_phrases).
   Also captures the rendered prompt so tests can assert on it."
  [!captured user-intent search-phrases]
  (fn [payload & _]
    (reset! !captured {:prompt (some (fn [m]
                                       (when (or (= "user" (name (:role m)))
                                                 (= :user (:role m)))
                                         (:content m)))
                                     (:messages payload))})
    {:choices
     [{:message
       {:tool_calls
        [{:function
          {:arguments
           (json/write-str
            {:user_intent user-intent
             :search_phrases search-phrases})}}]}}]}))

(defn- run-planner
  [!captured ctx user-intent phrases]
  (with-redefs [cfg/get stub-config
                client/create-chat-completion (mock-chat-completion !captured user-intent phrases)]
    (query-planner/execute-query-planner ctx)))

(deftest planner-disabled-returns-raw-query
  (testing ":enabled false skips the LLM entirely"
    (let [called? (atom false)]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion (tu/recording-fn (reset! called? true)
                                         (throw (Exception. "should not call")))]
        (let [result (query-planner/execute-query-planner
                      {:inputs {:query "What is X?"}
                       :parameters {:enabled false}
                       :skill-params {:tenant "test"}})]
          (is (= ["What is X?"] (get-in result [:outputs :queries])))
          (is (nil? (get-in result [:outputs :user-intent])))
          (is (true? (get-in result [:metadata :disabled])))
          (is (false? @called?)))))))

(deftest planner-user-intent-becomes-first-phrase
  (testing "The LLM-extracted :user_intent is prepended to :queries
            as the no-regression baseline against the literal query"
    (let [!captured (atom nil)
          result (run-planner
                  !captured
                  {:inputs {:query "How do I create a new dialog as a service owner?"
                            :conversation-history []}
                   :parameters {}
                   :skill-params {:tenant "test"}}
                  "Hvordan opprette dialog som tjenesteeier"
                  ["Creating dialogs" "create dialog API" "service owner dialog"])]
      (is (skills/result-success? result))
      (is (= "Hvordan opprette dialog som tjenesteeier"
             (get-in result [:outputs :user-intent])))
      (is (= "Hvordan opprette dialog som tjenesteeier"
             (first (get-in result [:outputs :queries])))
          "First phrase is the canonical user-intent")
      (is (= 4 (count (get-in result [:outputs :queries]))))
      (is (true? (get-in result [:metadata :had-llm-intent?]))))))

(deftest planner-dedupes-when-llm-emits-intent-twice
  (testing "When the LLM repeats the user-intent inside :search_phrases,
            :queries doesn't double it"
    (let [!captured (atom nil)
          result (run-planner
                  !captured
                  {:inputs {:query "Q?" :conversation-history []}
                   :parameters {}
                   :skill-params {:tenant "test"}}
                  "Canonical Q"
                  ["Canonical Q" "alt phrase 1" "alt phrase 2"])]
      (is (= ["Canonical Q" "alt phrase 1" "alt phrase 2"]
             (get-in result [:outputs :queries]))))))

(deftest planner-respects-max-phrases-cap
  (testing ":max-phrases caps total :queries (inclusive of user-intent slot)"
    (let [!captured (atom nil)
          result (run-planner
                  !captured
                  {:inputs {:query "Q?" :conversation-history []}
                   :parameters {:max-phrases 3}
                   :skill-params {:tenant "test"}}
                  "Canonical"
                  ["p1" "p2" "p3" "p4" "p5"])]
      ;; Combined dedup = [Canonical p1 p2 p3 p4 p5], capped at 3
      (is (= ["Canonical" "p1" "p2"] (get-in result [:outputs :queries]))))))

(deftest planner-empty-user-intent-still-emits-phrases
  (testing "Empty :user_intent → :queries is just the expansion phrases
            (no user-intent prepended); no-regression guarantee forfeited
            for that call but expansion still works"
    (let [!captured (atom nil)
          result (run-planner
                  !captured
                  {:inputs {:query "Q?" :conversation-history []}
                   :parameters {}
                   :skill-params {:tenant "test"}}
                  ""
                  ["expansion 1" "expansion 2"])]
      (is (nil? (get-in result [:outputs :user-intent])))
      (is (= ["expansion 1" "expansion 2"]
             (get-in result [:outputs :queries])))
      (is (false? (boolean (get-in result [:metadata :had-llm-intent?])))))))

(deftest planner-falls-back-on-malformed-json
  (testing "Malformed LLM JSON → :queries is [raw-query], :user-intent nil"
    (let [mock-call (fn [& _]
                      {:choices
                       [{:message
                         {:tool_calls
                          [{:function {:arguments "{not valid json"}}]}}]})]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion mock-call]
        (let [result (query-planner/execute-query-planner
                      {:inputs {:query "Raw query" :conversation-history []}
                       :parameters {}
                       :skill-params {:tenant "test"}})]
          (is (= ["Raw query"] (get-in result [:outputs :queries])))
          (is (nil? (get-in result [:outputs :user-intent])))
          (is (true? (get-in result [:metadata :fallback]))))))))

(deftest planner-falls-back-on-empty-tool-calls
  (testing "Empty tool-calls → fallback to raw query"
    (let [mock-call (fn [& _] {:choices [{:message {:tool_calls []}}]})]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion mock-call]
        (let [result (query-planner/execute-query-planner
                      {:inputs {:query "Raw" :conversation-history []}
                       :parameters {}
                       :skill-params {:tenant "test"}})]
          (is (= ["Raw"] (get-in result [:outputs :queries])))
          (is (true? (get-in result [:metadata :fallback]))))))))

(deftest planner-default-prompt-instructs-translation
  (testing "Default prompt tells the LLM to translate user-intent into
            the corpus language and to strip instructional scaffolding"
    (let [!captured (atom nil)
          _ (run-planner
             !captured
             {:inputs {:query "What is Dialogporten?" :conversation-history []}
              :parameters {:corpus-language "Norwegian (bokmål)"}
              :skill-params {:tenant "test"}}
             "Hva er Dialogporten"
             ["Dialogporten" "About Dialogporten"])
          prompt (:prompt @!captured)]
      (is (some? prompt))
      (is (str/includes? prompt "Norwegian (bokmål)")
          "Corpus-language parameter flows into the prompt")
      (is (re-find #"(?i)translate" prompt)
          "Prompt instructs translation")
      (is (re-find #"(?i)strip|drop" prompt)
          "Prompt instructs noise-stripping")
      (is (re-find #"(?i)search phrases" prompt)
          "Prompt asks for search phrases"))))

(deftest planner-corpus-language-parameter-flows-through
  (testing ":corpus-language parameter reaches the rendered prompt"
    (let [!captured (atom nil)
          _ (run-planner
             !captured
             {:inputs {:query "Q?" :conversation-history []}
              :parameters {:corpus-language "English"}
              :skill-params {:tenant "test"}}
             "Canonical"
             ["p1"])
          prompt (:prompt @!captured)]
      (is (str/includes? prompt "English")))))

(deftest planner-retries-transient-llm-errors
  (testing "Transient errors (HttpTimeoutException, IOException) are retried
            with exponential backoff; successful attempt's result wins"
    (let [attempts (atom 0)
          flaky-call (fn [& _]
                       (let [n (swap! attempts inc)]
                         (cond
                           (= 1 n) (throw (java.net.http.HttpTimeoutException. "first try timed out"))
                           (= 2 n) (throw (java.io.IOException. "second try IO error"))
                           :else {:choices
                                  [{:message
                                    {:tool_calls
                                     [{:function
                                       {:arguments
                                        (json/write-str
                                          {:user_intent "Recovered"
                                           :search_phrases ["alt 1" "alt 2"]})}}]}}]})))]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion flaky-call]
        (let [result (query-planner/execute-query-planner
                       {:inputs {:query "Q?" :conversation-history []}
                        :parameters {}
                        :skill-params {:tenant "test"}})]
          (is (= 3 @attempts) "Should have made 3 LLM call attempts before succeeding")
          (is (= "Recovered" (get-in result [:outputs :user-intent])))
          (is (= ["Recovered" "alt 1" "alt 2"] (get-in result [:outputs :queries])))
          (is (false? (boolean (get-in result [:metadata :fallback])))))))))

(deftest planner-falls-back-after-retry-exhaustion
  (testing "After max retries on transient errors, planner falls back to [raw-query]"
    (let [attempts (atom 0)
          always-timeout (fn [& _]
                           (swap! attempts inc)
                           (throw (java.net.http.HttpTimeoutException. "permanent")))]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion always-timeout]
        (let [result (query-planner/execute-query-planner
                       {:inputs {:query "Raw" :conversation-history []}
                        :parameters {}
                        :skill-params {:tenant "test"}})]
          ;; 1 initial attempt + 3 retries (per `retry-delays-ms`) = 4 calls
          (is (= 4 @attempts) "Should attempt initial + 3 retries before giving up")
          (is (= ["Raw"] (get-in result [:outputs :queries])))
          (is (nil? (get-in result [:outputs :user-intent])))
          (is (true? (get-in result [:metadata :fallback]))))))))

(deftest planner-does-not-retry-permanent-errors
  (testing "Non-transient errors (e.g. authentication, bad-request) skip retry"
    (let [attempts (atom 0)
          fatal-call (fn [& _]
                       (swap! attempts inc)
                       (throw (ex-info "Bad request: malformed schema" {})))]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion fatal-call]
        (let [result (query-planner/execute-query-planner
                       {:inputs {:query "Raw" :conversation-history []}
                        :parameters {}
                        :skill-params {:tenant "test"}})]
          (is (= 1 @attempts) "Permanent errors should not retry")
          (is (true? (get-in result [:metadata :fallback]))))))))

(deftest planner-preserves-custom-prompt-override
  (testing "Caller-provided :prompt replaces the default; {messages} still substitutes"
    (let [!captured (atom nil)
          _ (run-planner
             !captured
             {:inputs {:query "Hva heter hovedstaden?" :conversation-history []}
              :parameters {:prompt "CUSTOM: {messages}"}
              :skill-params {:tenant "test"}}
             "Canonical"
             ["a"])
          prompt (:prompt @!captured)]
      (is (re-find #"CUSTOM:" prompt)
          "Custom prompt replaces the default")
      (is (re-find #"Hva heter hovedstaden" prompt)
          "{messages} substitution still happens")
      (is (not (re-find #"(?i)translate" prompt))
          "Default-prompt-only guidance does not leak when overridden"))))

;; =============================================================================
;; Corpus-aware (PRF) expansion
;; =============================================================================
;; See plans/proposed/corpus-aware-prf-expansion-plan.md. Corpus-aware mode
;; runs TWO LLM calls (intent extraction + grounding) and harvests real corpus
;; phrases between them. The mocks distinguish the two LLM calls by tool name
;; ("planQueries" vs "groundedQueries") and stub the Typesense harvest.

(defn- mock-two-llm-calls
  "Stub `client/create-chat-completion` for both planner calls. The grounding call
   (tool `groundedQueries`) returns `grounded`; the intent call returns
   `intent` + `blind-phrases`. Records the ordered tool names in `!calls`."
  [!calls intent blind-phrases grounded]
  (fn [payload & _]
    (let [tool-name (-> payload :tools first :function :name)]
      (swap! !calls conj tool-name)
      (if (= tool-name "groundedQueries")
        {:choices [{:message {:tool_calls
                              [{:function {:arguments (json/write-str {:search_phrases grounded})}}]}}]}
        {:choices [{:message {:tool_calls
                              [{:function {:arguments (json/write-str {:user_intent intent
                                                                       :search_phrases blind-phrases})}}]}}]}))))

(defn- mock-harvest
  "Stub `retrieval/harvest-search-phrases`. Records each call's raw-filter
   in `!filters`. Returns conceptual phrases (docs D1/D2) for the hop-1 call
   (raw-filter nil) and procedural phrases for the hop-2 call (raw-filter set).
   Probe-ranks are set so D1 ranks above D2 (the planner aggregates by RRF
   over :probe-rank)."
  [!filters]
  (fn [_coll _queries & [raw-filter _opts]]
    (swap! !filters conj raw-filter)
    (if raw-filter
      [{:search-phrase "choose a component field to add the expression to"
        :doc-num "D1" :chunk-id "c3" :probe-rank 0}]
      [{:search-phrase "Show/hide fields based on form values" :doc-num "D1" :chunk-id "c1" :probe-rank 0}
       {:search-phrase "form field shown or hidden" :doc-num "D2" :chunk-id "c2" :probe-rank 1}])))

(deftest blind-mode-does-not-harvest
  (testing "expansion-mode :blind (default) makes no harvest call and one LLM call"
    (let [!calls (atom [])
          harvested? (atom false)]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion (mock-two-llm-calls !calls "Intent" ["a" "b"] ["x"])
                    retrieval/harvest-search-phrases (tu/recording-fn (reset! harvested? true) [])]
        (let [result (query-planner/execute-query-planner
                      {:inputs {:query "How do I X?" :phrases-collection "ph_coll"}
                       :parameters {} ; no expansion-mode => blind
                       :skill-params {:tenant "test"}})]
          (is (= ["Intent" "a" "b"] (get-in result [:outputs :queries]))
              "blind queries unchanged")
          (is (= ["planQueries"] @!calls) "only the intent LLM call runs")
          (is (false? @harvested?) "no PRF harvest in blind mode")
          (is (nil? (get-in result [:metadata :expansion-mode]))
              "blind metadata carries no expansion-mode key"))))))

(deftest corpus-aware-1hop-grounds-on-harvested-phrases
  (testing "1-hop: harvest once (no filter), ground, return user-intent + grounded"
    (let [!calls (atom [])
          !filters (atom [])]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion (mock-two-llm-calls
                                         !calls "Show field conditionally"
                                         ["conditional visibility"]
                                         ["dynamic expressions hidden property" "show/hide fields"])
                    retrieval/harvest-search-phrases (mock-harvest !filters)]
        (let [result (query-planner/execute-query-planner
                      {:inputs {:query "How do I show a field only when another has a value?"
                                :phrases-collection "ph_coll"}
                       :parameters {:expansion-mode :corpus-aware-1hop}
                       :skill-params {:tenant "test"}})]
          (is (= ["planQueries" "groundedQueries"] @!calls)
              "intent call then grounding call")
          (is (= [nil] @!filters) "exactly one harvest, with no doc scope (hop-1 only)")
          (is (= ["Show field conditionally"
                  "dynamic expressions hidden property"
                  "show/hide fields"]
                 (get-in result [:outputs :queries]))
              "queries = user-intent + grounded phrases")
          (is (= :corpus-aware-1hop (get-in result [:metadata :expansion-mode]))))))))

(deftest corpus-aware-2hop-scopes-to-hop1-docs-not-goldens
  (testing "2-hop: second harvest is scoped to the doc_nums hop-1 surfaced"
    (let [!calls (atom [])
          !filters (atom [])]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion (mock-two-llm-calls
                                         !calls "Show field conditionally"
                                         ["conditional visibility"]
                                         ["dynamic expressions" "choose a component field"])
                    retrieval/harvest-search-phrases (mock-harvest !filters)]
        (let [result (query-planner/execute-query-planner
                      {:inputs {:query "How do I show a field conditionally?"
                                :phrases-collection "ph_coll"}
                       :parameters {:expansion-mode :corpus-aware-2hop}
                       :skill-params {:tenant "test"}})]
          (is (= 2 (count @!filters)) "two harvests (hop-1 then hop-2)")
          (is (nil? (first @!filters)) "hop-1 has no doc scope")
          (is (= "doc_num:=[D1,D2]" (second @!filters))
              "hop-2 scopes to the docs hop-1 surfaced (D1,D2) — blindness guarantee")
          (is (= :corpus-aware-2hop (get-in result [:metadata :expansion-mode]))))))))

(deftest corpus-aware-falls-back-to-blind-without-phrases-collection
  (testing "no phrases-collection => blind fallback, no harvest, no grounding"
    (let [!calls (atom [])
          harvested? (atom false)]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion (mock-two-llm-calls !calls "Intent" ["a" "b"] ["x"])
                    retrieval/harvest-search-phrases (tu/recording-fn (reset! harvested? true) [])]
        (let [result (query-planner/execute-query-planner
                      {:inputs {:query "How do I X?"} ; no :phrases-collection
                       :parameters {:expansion-mode :corpus-aware-2hop}
                       :skill-params {:tenant "test"}})]
          (is (= ["Intent" "a" "b"] (get-in result [:outputs :queries]))
              "returns the blind queries")
          (is (= ["planQueries"] @!calls) "no grounding call")
          (is (false? @harvested?) "no harvest attempted")
          (is (= :blind (get-in result [:metadata :expansion-mode])))
          (is (true? (get-in result [:metadata :corpus-aware-fallback]))
              "fallback is flagged so transcripts aren't misread"))))))

(deftest corpus-aware-falls-back-when-harvest-empty
  (testing "empty harvest => blind fallback, no grounding call"
    (let [!calls (atom [])]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion (mock-two-llm-calls !calls "Intent" ["a"] ["x"])
                    retrieval/harvest-search-phrases (tu/recording-fn [])]
        (let [result (query-planner/execute-query-planner
                      {:inputs {:query "How do I X?" :phrases-collection "ph_coll"}
                       :parameters {:expansion-mode :corpus-aware-1hop}
                       :skill-params {:tenant "test"}})]
          (is (= ["Intent" "a"] (get-in result [:outputs :queries])))
          (is (= ["planQueries"] @!calls) "grounding skipped when nothing harvested")
          (is (true? (get-in result [:metadata :corpus-aware-fallback]))))))))

;; =============================================================================
;; Single-call variant (Design 1): :expansion-variant :one-call
;; =============================================================================
;; One LLM call total: the grounding+intent call reuses the {user_intent,
;; search_phrases} tool (name "planQueries"), so mock-two-llm-calls' non-grounding
;; branch supplies intent + phrases. No separate blind-expansion call.

(deftest one-call-makes-a-single-llm-call
  (testing ":one-call skips blind expansion → exactly ONE LLM call, queries = intent + grounded"
    (let [!calls (atom []) !filters (atom [])]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion (mock-two-llm-calls
                                         !calls "Show field conditionally"
                                         ["dynamic expressions hidden" "show/hide fields"]
                                         ["UNUSED"])
                    retrieval/harvest-search-phrases (mock-harvest !filters)]
        (let [result (query-planner/execute-query-planner
                      {:inputs {:query "How do I show a field conditionally?"
                                :phrases-collection "ph_coll"}
                       :parameters {:expansion-mode :corpus-aware-1hop
                                    :expansion-variant :one-call}
                       :skill-params {:tenant "test"}})]
          (is (= ["planQueries"] @!calls) "exactly one LLM call (no blind-expansion call)")
          (is (= [nil] @!filters) "one harvest, no doc scope (1hop)")
          (is (= "Show field conditionally" (get-in result [:outputs :user-intent])))
          (is (= ["Show field conditionally" "dynamic expressions hidden" "show/hide fields"]
                 (get-in result [:outputs :queries])))
          (is (= :one-call (get-in result [:metadata :expansion-variant]))))))))

(deftest one-call-2hop-still-one-call-and-scopes-hop2
  (testing ":one-call :corpus-aware-2hop = still ONE LLM call; hop-2 scopes to hop-1 docs"
    (let [!calls (atom []) !filters (atom [])]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion (mock-two-llm-calls !calls "intent" ["p1" "p2"] ["x"])
                    retrieval/harvest-search-phrases (mock-harvest !filters)]
        (let [result (query-planner/execute-query-planner
                      {:inputs {:query "How do I show a field conditionally?"
                                :phrases-collection "ph_coll"}
                       :parameters {:expansion-mode :corpus-aware-2hop
                                    :expansion-variant :one-call}
                       :skill-params {:tenant "test"}})]
          (is (= ["planQueries"] @!calls) "still exactly one LLM call")
          (is (= 2 (count @!filters)) "two harvests (hop-1 + hop-2)")
          (is (= "doc_num:=[D1,D2]" (second @!filters)) "hop-2 blind-scoped to hop-1 docs")
          (is (= :corpus-aware-2hop (get-in result [:metadata :expansion-mode]))))))))

(deftest one-call-empty-harvest-falls-back-to-raw-without-llm
  (testing ":one-call with empty harvest → raw-query fallback, no LLM call at all"
    (let [!calls (atom [])]
      (with-redefs [cfg/get stub-config
                    client/create-chat-completion (mock-two-llm-calls !calls "intent" ["p1"] ["x"])
                    retrieval/harvest-search-phrases (tu/recording-fn [])]
        (let [result (query-planner/execute-query-planner
                      {:inputs {:query "Q?" :phrases-collection "ph_coll"}
                       :parameters {:expansion-mode :corpus-aware-1hop
                                    :expansion-variant :one-call}
                       :skill-params {:tenant "test"}})]
          (is (= ["Q?"] (get-in result [:outputs :queries])) "raw-query fallback")
          (is (empty? @!calls) "no LLM call when nothing harvested")
          (is (true? (get-in result [:metadata :corpus-aware-fallback]))))))))

;; =============================================================================
;; The prompt actually contains what it says it contains (#75)
;;
;; `when-not` returns only its LAST form, so
;;
;;   (when-not translate?
;;     "Every phrase MUST be in the same language as the user's message "
;;     "(see LANGUAGE rule above). ")
;;
;; discarded the first half. On the default path the prompt shipped a dangling
;; "(see LANGUAGE rule above)." with the rule it referred to missing, and the
;; planner has been consuming that prompt for every prior evaluation.
;;
;; These read the built prompt rather than the source, because the source
;; looked correct - that is precisely how this survived.
;; =============================================================================

(def ^:private same-language-instruction
  "Every phrase MUST be in the same language as the user's message")

(defn- prompt-for
  [corpus-language]
  (#'digdir.skills.builtin.query-planner/build-default-prompt
    "user: Hva er Dialogporten?" corpus-language false))

(deftest phrase-generation-keeps-its-same-language-instruction
  (testing "default (non-translate) path carries the whole instruction"
    (let [prompt (prompt-for nil)]
      (is (str/includes? prompt same-language-instruction)
          "the instruction the cross-reference points at must be present")
      (is (str/includes? prompt "(see LANGUAGE rule above)"))
      (is (str/includes? prompt (str same-language-instruction
                                     " (see LANGUAGE rule above)."))
          "and the two halves must arrive together, not just both somewhere")))

  (testing "no dangling cross-reference on either path"
    ;; The failure shape: a reference to a rule with the rule missing.
    (doseq [corpus-language [nil "" "norsk"]]
      (let [prompt (prompt-for corpus-language)]
        (when (str/includes? prompt "(see LANGUAGE rule above)")
          (is (str/includes? prompt same-language-instruction)
              (str "corpus-language " (pr-str corpus-language)
                   " leaves a cross-reference pointing at nothing"))))))

  (testing "the translate path substitutes its own rule rather than this one"
    (let [prompt (prompt-for "norsk")]
      (is (str/includes? prompt "Translate it to the corpus language: norsk"))
      (is (not (str/includes? prompt "(see LANGUAGE rule above)"))
          "translating means phrases follow the corpus language, not the user's"))))
