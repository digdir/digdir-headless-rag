(ns digdir.docs.pipeline.search-phrases-test
  "Tests for digdir.docs.pipeline.search-phrases - LLM search phrase generation."
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [digdir.docs.pipeline.search-phrases :as sp]
            [digdir.docs.pipeline.core :as core]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def test-cache-dir "cache/test-search-phrases/")

(defn cleanup-test-cache [f]
  ;; Clean up before
  (let [dir (io/file test-cache-dir)]
    (when (.exists dir)
      (doseq [file (.listFiles dir)]
        (.delete file))
      (.delete dir)))
  (f)
  ;; Clean up after
  (let [dir (io/file test-cache-dir)]
    (when (.exists dir)
      (doseq [file (.listFiles dir)]
        (.delete file))
      (.delete dir))))

(use-fixtures :each cleanup-test-cache)

;; ============================================================================
;; parse-phrases-response Tests
;; ============================================================================

(deftest parse-phrases-response-basic
  (testing "Parses comma-separated phrases"
    (let [response {:choices [{:message {:content "phrase one, phrase two, phrase three"}}]}
          phrases (sp/parse-phrases-response response)]
      (is (= 3 (count phrases)))
      (is (= "phrase one" (first phrases)))
      (is (= "phrase three" (last phrases))))))

(deftest parse-phrases-response-trims-whitespace
  (testing "Trims whitespace from phrases"
    (let [response {:choices [{:message {:content "  phrase one  ,  phrase two  "}}]}
          phrases (sp/parse-phrases-response response)]
      (is (= "phrase one" (first phrases)))
      (is (= "phrase two" (second phrases))))))

(deftest parse-phrases-response-multiline
  (testing "Takes last line when multiline"
    (let [response {:choices [{:message {:content "Some explanation here.\n\nphrase a, phrase b"}}]}
          phrases (sp/parse-phrases-response response)]
      (is (= ["phrase a" "phrase b"] phrases)))))

(deftest parse-phrases-response-single-phrase
  (testing "Handles single phrase"
    (let [response {:choices [{:message {:content "single phrase"}}]}
          phrases (sp/parse-phrases-response response)]
      (is (= ["single phrase"] phrases)))))

;; ============================================================================
;; cache-key Tests
;; ============================================================================

(deftest cache-key-deterministic
  (testing "Same inputs produce same key"
    (let [chunk {:chunk_id "test-chunk"}
          model "gpt-4"
          prompt "test prompt"
          key1 (sp/cache-key chunk model prompt)
          key2 (sp/cache-key chunk model prompt)]
      (is (= key1 key2)))))

(deftest cache-key-includes-chunk-id
  (testing "Key includes chunk ID"
    (let [chunk {:chunk_id "my-chunk-123"}
          key (sp/cache-key chunk "model" "prompt")]
      (is (str/starts-with? key "my-chunk-123-")))))

(deftest cache-key-different-models
  (testing "Different models produce different keys"
    (let [chunk {:chunk_id "test"}
          key1 (sp/cache-key chunk "gpt-4" "prompt")
          key2 (sp/cache-key chunk "gpt-3.5" "prompt")]
      (is (not= key1 key2)))))

(deftest cache-key-different-prompts
  (testing "Different prompts produce different keys"
    (let [chunk {:chunk_id "test"}
          key1 (sp/cache-key chunk "model" "prompt 1")
          key2 (sp/cache-key chunk "model" "prompt 2")]
      (is (not= key1 key2)))))

;; ============================================================================
;; ensure-cache-dir! Tests
;; ============================================================================

(deftest ensure-cache-dir-creates-directory
  (testing "Creates cache directory if not exists"
    (let [test-dir "cache/test-ensure-dir/"]
      (sp/ensure-cache-dir! test-dir)
      (is (.exists (io/file test-dir)))
      ;; Cleanup
      (.delete (io/file test-dir)))))

(deftest ensure-cache-dir-idempotent
  (testing "Safe to call multiple times"
    (let [test-dir "cache/test-ensure-dir-2/"]
      (sp/ensure-cache-dir! test-dir)
      (sp/ensure-cache-dir! test-dir)
      (is (.exists (io/file test-dir)))
      ;; Cleanup
      (.delete (io/file test-dir)))))

;; ============================================================================
;; read-cached-phrases / write-cached-phrases! Tests
;; ============================================================================

(deftest write-and-read-cached-phrases
  (testing "Can write and read cached phrases"
    (let [cache-path (str test-cache-dir "test-cache.edn")
          phrases ["phrase 1" "phrase 2" "phrase 3"]]
      (sp/ensure-cache-dir! test-cache-dir)
      (sp/write-cached-phrases! cache-path phrases)
      (let [read-phrases (sp/read-cached-phrases cache-path)]
        (is (= phrases read-phrases))))))

(deftest read-cached-phrases-returns-nil-for-missing
  (testing "Returns nil for missing cache file"
    (is (nil? (sp/read-cached-phrases "nonexistent/path.edn")))))

;; ============================================================================
;; default-search-phrases-prompt Tests
;; ============================================================================

(deftest default-prompt-exists
  (testing "Default prompt is defined"
    (is (string? sp/default-search-phrases-prompt))
    (is (pos? (count sp/default-search-phrases-prompt)))))

(deftest default-prompt-has-placeholder
  (testing "Default prompt contains REPLACE_ME placeholder"
    (is (str/includes? sp/default-search-phrases-prompt "REPLACE_ME"))))

;; ============================================================================
;; openai-implementations Tests
;; ============================================================================

(deftest openai-implementations-defined
  (testing "OpenAI implementations are defined"
    (is (map? sp/openai-implementations))
    (is (contains? sp/openai-implementations :azure-openai))
    (is (contains? sp/openai-implementations :openrouter))))

(deftest azure-openai-config
  (testing "Azure OpenAI has required config"
    (let [azure (:azure-openai sp/openai-implementations)]
      (is (contains? azure :api-key))
      (is (contains? azure :api-endpoint))
      (is (= :azure (:impl azure))))))

(deftest openrouter-config
  (testing "OpenRouter has required config"
    (let [or (:openrouter sp/openai-implementations)]
      (is (contains? or :api-key))
      (is (= "https://openrouter.ai/api/v1" (:api-endpoint or))))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest search-phrase-workflow
  (testing "Complete workflow from chunk to cached phrases"
    (let [chunk {:chunk_id "test-workflow-chunk"
                 :content_markdown "How to configure authentication"}
          config {:search-phrases/model "gpt-4"
                  :search-phrases/prompt "Generate phrases: REPLACE_ME"}
          cache-dir test-cache-dir
          cache-path (str cache-dir
                          (sp/cache-key chunk
                                        (:search-phrases/model config)
                                        (:search-phrases/prompt config))
                          ".edn")
          phrases ["authentication setup" "config auth"]]
      ;; Setup: write to cache
      (sp/ensure-cache-dir! cache-dir)
      (sp/write-cached-phrases! cache-path phrases)
      ;; Read should work
      (let [cached (sp/read-cached-phrases cache-path)]
        (is (= phrases cached))))))
