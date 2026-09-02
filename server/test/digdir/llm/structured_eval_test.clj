(ns digdir.llm.structured-eval-test
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.llm.structured-eval :as se]
            [digdir.skills.test-helpers :as th]))

(deftest strip-code-fences-tolerates-markdown-wrapping
  (testing "Plain JSON passes through unchanged"
    (is (= "{\"a\":1}" (se/strip-code-fences "{\"a\":1}"))))
  (testing "Unfenced with surrounding whitespace"
    (is (= "{\"a\":1}" (se/strip-code-fences "  {\"a\":1}  "))))
  (testing "```json ... ``` fences get stripped"
    (is (= "{\"a\":1}" (se/strip-code-fences "```json\n{\"a\":1}\n```"))))
  (testing "Bare ``` fences get stripped"
    (is (= "{\"a\":1}" (se/strip-code-fences "```\n{\"a\":1}\n```"))))
  (testing "Nil input returns empty string"
    (is (= "" (se/strip-code-fences nil)))))

(deftest parse-json-response-handles-fenced-output
  (testing "Plain JSON"
    (is (= {:status "ok"} (se/parse-json-response "{\"status\":\"ok\"}"))))
  (testing "Markdown-fenced JSON"
    (is (= {:status "ok"} (se/parse-json-response "```json\n{\"status\":\"ok\"}\n```"))))
  (testing "JSON surrounded by commentary"
    (is (= {:status "ok"}
           (se/parse-json-response "Here is your answer:\n{\"status\":\"ok\"}\nLet me know if…"))))
  (testing "Unparseable input raises a :json-parse error"
    (let [caught (try
                   (se/parse-json-response "totally not json")
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? caught))
      (is (= :json-parse (:stage (ex-data caught)))))))

(deftest keyword-like-normalizes-case-and-underscores
  (is (= :support-found (se/keyword-like "Support_Found")))
  (is (= :support-found (se/keyword-like "SUPPORT_FOUND")))
  (is (= :support-found (se/keyword-like "support-found")))
  (is (= :support-found (se/keyword-like :Support_Found)))
  (is (= :support-found (se/keyword-like "  support-found  ")))
  (is (nil? (se/keyword-like nil)))
  (is (nil? (se/keyword-like 42))))

(deftest validated-enum-accepts-variants-rejects-unknown
  (testing "Known variants pass"
    (is (= :sufficient
           (se/->validated-enum "Sufficient" #{:sufficient :insufficient} :status)))
    (is (= :sufficient
           (se/->validated-enum :SUFFICIENT #{:sufficient :insufficient} :status))))
  (testing "Unknown value raises :enum-validation"
    (let [caught (try
                   (se/->validated-enum "unknown" #{:a :b} :field)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? caught))
      (is (= :enum-validation (:stage (ex-data caught))))
      (is (= :field (:field (ex-data caught)))))))

(deftest resolve-alias-handles-semantic-aliases
  (testing "Alias wins over basic normalization"
    (is (= :sufficient (se/resolve-alias-or-normalize :enough {:enough :sufficient}))))
  (testing "Unknown falls through to keyword-like"
    (is (= :custom-status (se/resolve-alias-or-normalize "Custom_Status" {})))))

(deftest clamp-confidence-handles-strings-and-clamps
  (is (= 0.82 (se/clamp-confidence 0.82)))
  (is (= 0.82 (se/clamp-confidence "0.82")))
  (is (= 1.0 (se/clamp-confidence 1.5)))
  (is (= 0.0 (se/clamp-confidence -0.3)))
  (is (thrown? clojure.lang.ExceptionInfo (se/clamp-confidence "not a number"))))

;; =============================================================================
;; evaluate envelope
;; =============================================================================

(deftest evaluate-happy-path-calls-normalize
  (let [result (se/evaluate
                {:label "test-eval"
                 :llm-fn (th/llm-returning-json "{\"decision\":\"Yes\"}")
                 :system-prompt "sys"
                 :user-prompt "usr"
                 :normalize-fn (fn [parsed]
                                 {:verdict (se/keyword-like (:decision parsed))
                                  :degraded? false})
                 :fallback-fn (fn [_] {:verdict :unknown :degraded? true})})]
    (is (= :yes (:verdict result)))
    (is (false? (:degraded? result)))))

(deftest evaluate-json-error-falls-back
  (let [result (se/evaluate
                {:label "test-eval"
                 :llm-fn (th/llm-returning-json "not json at all")
                 :system-prompt "sys"
                 :user-prompt "usr"
                 :normalize-fn (fn [_] (throw (ex-info "should not reach" {})))
                 :fallback-fn (fn [info]
                                {:verdict :unknown
                                 :degraded? true
                                 :reason (:stage info)})})]
    (is (true? (:degraded? result)))
    (is (= :json-parse (:reason result)))))

(deftest evaluate-llm-exception-falls-back
  (let [result (se/evaluate
                {:label "test-eval"
                 :llm-fn (th/llm-throwing "LLM timeout")
                 :system-prompt "sys"
                 :user-prompt "usr"
                 :normalize-fn (fn [_] (throw (ex-info "should not reach" {})))
                 :fallback-fn (fn [info]
                                {:verdict :unknown
                                 :degraded? true
                                 :reason (:stage info)})})]
    (is (true? (:degraded? result)))
    (is (= :llm-call (:reason result)))))

(deftest evaluate-enum-error-falls-back-with-stage
  (let [result (se/evaluate
                {:label "test-eval"
                 :llm-fn (th/llm-returning-json "{\"status\":\"wtf\"}")
                 :system-prompt "sys"
                 :user-prompt "usr"
                 :normalize-fn (fn [parsed]
                                 (se/->validated-enum (:status parsed)
                                                      #{:ok :error}
                                                      :status))
                 :fallback-fn (fn [info]
                                {:verdict :unknown
                                 :degraded? true
                                 :reason (:stage info)})})]
    (is (true? (:degraded? result)))
    (is (= :enum-validation (:reason result)))))
