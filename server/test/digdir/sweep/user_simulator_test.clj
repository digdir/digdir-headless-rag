(ns digdir.sweep.user-simulator-test
  (:require [digdir.test-utils :as tu]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [digdir.skills.builtin.agent.loop :as agent-loop]
            [digdir.sweep.user-simulator :as sim]))

(def stub-resp
  {:choices [{:message {:role "assistant"
                        :content "Jeg vil vite hva Dialogporten er, helt enkelt."}
              :finish_reason "stop"}]
   :usage {:prompt_tokens 120 :completion_tokens 18}})

(deftest reply-extraction-trims-and-returns-content
  (testing "respond-to-clarification returns the trimmed LLM content
            and surfaces usage. We stub call-llm so the test runs
            without a live Azure config."
    (with-redefs [agent-loop/call-llm (tu/recording-fn stub-resp)]
      (let [result (sim/respond-to-clarification
                     {:tenant "digdir"
                      :original-query "Hva er Dialogporten?"
                      :clarification-question "Hva vil du vite om Dialogporten?"})]
        (is (= "Jeg vil vite hva Dialogporten er, helt enkelt." (:reply result)))
        (is (nil? (:error result)))
        (is (= 120 (get-in result [:usage :prompt_tokens])))
        (is (= 18 (get-in result [:usage :completion_tokens]))))))

  (testing "Whitespace and empty responses produce :error"
    (with-redefs [agent-loop/call-llm
                  (fn [& _]
                    {:choices [{:message {:role "assistant" :content "   "}
                                :finish_reason "stop"}]})]
      (let [result (sim/respond-to-clarification
                     {:tenant "digdir"
                      :original-query "Hva er Dialogporten?"
                      :clarification-question "Hva vil du vite?"})]
        (is (nil? (:reply result)))
        (is (some? (:error result)))))))

(deftest llm-exception-is-captured-not-rethrown
  (testing "A failing LLM call returns an :error map rather than
            propagating the exception. The sweep runner needs that to
            keep moving across thousands of rows without one Azure
            blip ending the whole sweep."
    (with-redefs [agent-loop/call-llm (tu/recording-fn (throw (ex-info "azure 503" {:status 503})))]
      (let [result (sim/respond-to-clarification
                     {:tenant "digdir"
                      :original-query "x"
                      :clarification-question "y"})]
        (is (nil? (:reply result)))
        (is (str/includes? (:error result) "azure 503"))))))

(deftest required-inputs-are-validated-eagerly
  (testing "Missing tenant / blank queries throw before hitting the LLM."
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":tenant is required"
                          (sim/respond-to-clarification
                            {:original-query "x"
                             :clarification-question "y"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":original-query"
                          (sim/respond-to-clarification
                            {:tenant "digdir"
                             :original-query "  "
                             :clarification-question "y"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":clarification-question"
                          (sim/respond-to-clarification
                            {:tenant "digdir"
                             :original-query "x"
                             :clarification-question ""})))))

(deftest system-prompt-detects-norwegian-and-asks-for-norwegian-reply
  (testing "Norwegian queries trigger 'Svar på norsk' in the system prompt
            so the simulator stays in Norwegian. Non-Norwegian queries
            get an English instruction. We capture the call args via
            with-redefs."
    (let [captured (atom nil)
          stub (fn [_tenant messages _tools _model _temp]
                 (reset! captured messages)
                 stub-resp)]
      (with-redefs [agent-loop/call-llm stub]
        (sim/respond-to-clarification
          {:tenant "digdir"
           :original-query "Hva er Dialogporten?"
           :clarification-question "Hva vil du vite?"})
        (let [system (-> @captured first :content)]
          (is (str/includes? system "Svar på norsk"))
          (is (not (str/includes? system "Reply in English")))))
      (with-redefs [agent-loop/call-llm stub]
        (sim/respond-to-clarification
          {:tenant "digdir"
           :original-query "What is Dialogporten?"
           :clarification-question "What do you want to know?"})
        (let [system (-> @captured first :content)]
          (is (str/includes? system "Reply in English")))))))

(deftest intent-hint-is-included-only-in-system-prompt
  (testing "When an :intent-hint is provided it appears in the system
            message but not in the user-visible assistant turn. The
            assistant turn is what the simulator 'sees' from the agent."
    (let [captured (atom nil)
          stub (fn [_t messages _tools _model _temp]
                 (reset! captured messages)
                 stub-resp)]
      (with-redefs [agent-loop/call-llm stub]
        (sim/respond-to-clarification
          {:tenant "digdir"
           :original-query "Hva er Dialogporten?"
           :clarification-question "Hva vil du vite?"
           :intent-hint "the simple definition of Dialogporten"})
        (let [[system assistant] @captured]
          (is (str/includes? (:content system)
                             "the simple definition of Dialogporten"))
          (is (not (str/includes? (:content assistant)
                                  "the simple definition of Dialogporten"))
              "Hint must not leak into the assistant-side message"))))))

(deftest intent-hint-from-row-prefers-notes
  (testing "intent-hint-from-row prefers :notes when present; falls back
            to extracted regex tokens; finally falls back to topic tags."
    (is (= "Stable at rank=53 — rerank tuning canary"
           (sim/intent-hint-from-row
             {:notes "Stable at rank=53 — rerank tuning canary"
              :expected-answer-pattern "(?is)(publis|lest)"
              :tags #{:simple :altinn-correspondence}})))

    (let [hint (sim/intent-hint-from-row
                 {:expected-answer-pattern "(?is)(transition\\s*service\\s*bridge|servicecode|myk\\s+overgang)"
                  :tags #{:simple :altinn-broker}})]
      (is (str/starts-with? hint "phrases like "))
      (is (str/includes? hint "transition")))

    (let [hint (sim/intent-hint-from-row
                 {:expected-answer-pattern nil
                  :tags #{:simple :altinn-broker}})]
      (is (str/starts-with? hint "topic: ")))

    (is (nil? (sim/intent-hint-from-row {})))))

;; ---------------------------------------------------------------------
;; LIVE test — only runs when the dev server is up and reachable.
;; Drive this from the REPL when iterating; it isn't gated into bb test.
;;
;;   (require 'digdir.sweep.user-simulator :reload)
;;   (digdir.sweep.user-simulator/respond-to-clarification
;;     {:tenant "digdir"
;;      :original-query "Hva er Dialogporten?"
;;      :clarification-question "Hva vil du vite om Dialogporten?"
;;      :clarification-context "Available topics: Dialogporten, Broker, ..."})
;;
;; Expected: a short Norwegian reply that picks a concrete sub-question
;; (e.g. "Bare en kort definisjon — hva er det og hva brukes det til.").
;; ---------------------------------------------------------------------
