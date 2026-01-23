(ns digdir.docs.chunking-test
  "Tests for document chunking functionality."
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.string :as str]
            [digdir.rag.chunking :as chunking]
            [digdir.docs.test-fixtures :as fixtures]))

;; ============================================================================
;; split-into-chunks-by-headers Tests
;; ============================================================================

(deftest chunks-by-headers-basic
  (testing "Splits document by headers"
    (let [config {:chunks/minimum-length 10}
          doc {:page-content fixtures/sample-markdown-simple}
          chunks (chunking/split-into-chunks-by-headers config [doc])]
      (is (vector? chunks))
      (is (pos? (count chunks)))
      (is (every? :page-content chunks))
      (is (every? :metadata chunks)))))

(deftest chunks-by-headers-preserves-content
  (testing "Chunks contain the original content"
    (let [config {:chunks/minimum-length 10}
          doc {:page-content "# Title\n\nContent under title.\n\n## Section\n\nSection content."}
          chunks (chunking/split-into-chunks-by-headers config [doc])
          combined-content (str/join " " (map :page-content chunks))]
      ;; Original content should be present in chunks
      (is (str/includes? combined-content "Content under title"))
      (is (str/includes? combined-content "Section content")))))

(deftest chunks-by-headers-metadata
  (testing "Chunks have metadata with header info"
    (let [config {:chunks/minimum-length 10}
          doc {:page-content "# Main\n\nIntro.\n\n## Section 1\n\nContent 1.\n\n## Section 2\n\nContent 2."}
          chunks (chunking/split-into-chunks-by-headers config [doc])
          metadata-values (map :metadata chunks)]
      ;; Some chunks should have metadata
      (is (some #(not (empty? %)) metadata-values)))))

(deftest chunks-by-headers-no-headers
  (testing "Document without headers produces single chunk"
    (let [config {:chunks/minimum-length 10}
          doc {:page-content "Just plain text without any headers.\n\nMore plain text."}
          chunks (chunking/split-into-chunks-by-headers config [doc])]
      (is (= 1 (count chunks)))
      (is (str/includes? (:page-content (first chunks)) "Just plain text")))))

(deftest chunks-by-headers-nested-headers
  (testing "Handles nested headers (h1, h2, h3)"
    (let [config {:chunks/minimum-length 10}
          doc {:page-content fixtures/sample-markdown-nested}
          chunks (chunking/split-into-chunks-by-headers config [doc])]
      ;; Should create multiple chunks for nested structure
      (is (> (count chunks) 1)))))

(deftest chunks-by-headers-code-blocks
  (testing "Handles headers in code blocks (current behavior: splits on them)"
    (let [config {:chunks/minimum-length 10}
          doc {:page-content "# Real Header\n\nIntro.\n\n```markdown\n# Fake Header In Code\nCode content\n```\n\nMore content."}
          chunks (chunking/split-into-chunks-by-headers config [doc])]
      ;; Note: Current implementation splits on headers even inside code blocks
      ;; This test documents current behavior - fixing this would be a separate task
      (is (pos? (count chunks)))
      ;; The code content should be somewhere in the chunks
      (is (some #(str/includes? (:page-content %) "Code content") chunks)))))

;; ============================================================================
;; concatenate-too-small-chunks Tests
;; ============================================================================

(deftest concatenate-small-chunks-basic
  (testing "Concatenates chunks smaller than minimum length"
    (let [config {:chunks/minimum-length 50}
          chunks [{:page-content "Short" :metadata {}}
                  {:page-content "Also short" :metadata {}}
                  {:page-content "This is a much longer chunk that should exceed the minimum length requirement" :metadata {}}]
          result (chunking/concatenate-too-small-chunks config chunks)]
      ;; First two short chunks should be concatenated
      (is (<= (count result) 2)))))

(deftest concatenate-small-chunks-preserves-large
  (testing "Preserves chunks that meet minimum length"
    (let [config {:chunks/minimum-length 10}
          large-chunk {:page-content "This is a sufficiently long chunk that exceeds the minimum." :metadata {:header "Test"}}
          chunks [large-chunk]
          result (chunking/concatenate-too-small-chunks config chunks)]
      (is (= 1 (count result)))
      (is (= "This is a sufficiently long chunk that exceeds the minimum."
             (:page-content (first result)))))))

(deftest concatenate-small-chunks-empty
  (testing "Handles empty input"
    (let [config {:chunks/minimum-length 50}
          result (chunking/concatenate-too-small-chunks config [])]
      (is (empty? result)))))

;; ============================================================================
;; Integration: Chunking in document loaders
;; ============================================================================

(deftest chunking-workflow-folder
  (testing "Chunking works with folder-style document"
    (let [config {:chunks/minimum-length 30}
          doc {:page-content fixtures/sample-markdown-simple}
          chunks (chunking/split-into-chunks-by-headers config [doc])]
      (is (pos? (count chunks)))
      ;; Each chunk should be at least minimum length or be concatenated
      (doseq [chunk chunks]
        (let [content (:page-content chunk)]
          ;; Either meets minimum or is the only/last chunk
          (is (string? content)))))))

(deftest chunking-workflow-website
  (testing "Chunking works with website-style document"
    (let [config {:chunks/minimum-length 30}
          doc {:page-content fixtures/sample-markdown-nested}
          chunks (chunking/split-into-chunks-by-headers config [doc])]
      (is (pos? (count chunks))))))

(deftest chunking-produces-valid-chunks
  (testing "All chunks have required fields"
    (let [config {:chunks/minimum-length 20}
          doc {:page-content fixtures/sample-markdown-simple}
          chunks (chunking/split-into-chunks-by-headers config [doc])]
      (doseq [chunk chunks]
        (is (contains? chunk :page-content))
        (is (contains? chunk :metadata))
        (is (string? (:page-content chunk)))
        (is (map? (:metadata chunk)))))))

;; ============================================================================
;; Edge Cases
;; ============================================================================

(deftest chunking-empty-document
  (testing "Handles empty document"
    (let [config {:chunks/minimum-length 10}
          doc {:page-content ""}
          chunks (chunking/split-into-chunks-by-headers config [doc])]
      ;; Should return empty or a single empty chunk
      (is (<= (count chunks) 1)))))

(deftest chunking-only-headers
  (testing "Handles document with only headers"
    (let [config {:chunks/minimum-length 10}
          doc {:page-content "# Header 1\n\n## Header 2\n\n### Header 3"}
          chunks (chunking/split-into-chunks-by-headers config [doc])]
      ;; Should still create chunks, even if mostly empty
      (is (vector? chunks)))))

(deftest chunking-special-characters-in-headers
  (testing "Handles special characters in headers"
    (let [config {:chunks/minimum-length 10}
          doc {:page-content "# Header with [link](url) and *emphasis*\n\nContent here."}
          chunks (chunking/split-into-chunks-by-headers config [doc])]
      (is (pos? (count chunks))))))

(deftest chunking-norwegian-content
  (testing "Handles Norwegian characters"
    (let [config {:chunks/minimum-length 10}
          doc {:page-content "# Blåbærsyltetøy\n\nÆrlighet varer lengst.\n\n## Økonomi\n\nÅrlig rapport."}
          chunks (chunking/split-into-chunks-by-headers config [doc])]
      (is (pos? (count chunks)))
      (let [all-content (str/join " " (map :page-content chunks))]
        (is (str/includes? all-content "Ærlighet"))))))
