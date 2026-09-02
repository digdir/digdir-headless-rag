(ns digdir.import-export.format.jsonl-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.import-export.format.jsonl :as jsonl])
  (:import [java.io File]))

(defn- temp-file ^File []
  (doto (File/createTempFile "jsonl-test-" ".jsonl")
    (.deleteOnExit)))

(deftest namespaced-keys-roundtrip
  (testing "namespaced keys survive write+read"
    (let [records [{:conversation/id "c1"
                    :conversation/topic "Hello"
                    :conversation/created 1}
                   {:conversation/id "c2"
                    :conversation/topic "World"
                    :conversation/created 2}]
          f (temp-file)]
      (jsonl/write-records f records)
      (is (= records (jsonl/read-records f))))))

(deftest deep-nesting-roundtrip
  (testing "conversation -> messages -> chunks shape round-trips intact"
    (let [conv {:conversation/id "c1"
                :conversation/agent-id "builtin/agent-rag-agent"
                :conversation/messages
                [{:message/id "m1"
                  :message/role "user"
                  :message/text "What is RAG?"
                  :message/chunks []}
                 {:message/id "m2"
                  :message/role "assistant"
                  :message/text "Retrieval-Augmented Generation."
                  :message/chunks
                  [{:chunk/doc-num 1
                    :chunk/url "https://example.com/rag"
                    :chunk/content-markdown "# RAG\n\nA technique that..."}
                   {:chunk/doc-num 2
                    :chunk/url "https://example.com/llm"
                    :chunk/content-markdown "## LLM\n\nLarge language..."}]}]}
          f (temp-file)]
      (jsonl/write-records f [conv])
      (is (= [conv] (jsonl/read-records f))))))

(deftest one-record-per-line
  (testing "each record occupies exactly one line"
    (let [records (mapv #(hash-map :conversation/id (str "c" %)) (range 5))
          f (temp-file)
          _ (jsonl/write-records f records)
          content (slurp f)]
      (is (= 5 (count (str/split-lines content))))
      (is (str/ends-with? content "\n")))))

(deftest blank-lines-tolerated
  (testing "blank lines in input are skipped during read"
    (let [f (temp-file)]
      (spit f
            (str "{\"conversation/id\":\"c1\"}\n"
                 "\n"
                 "{\"conversation/id\":\"c2\"}\n"
                 "   \n"
                 "{\"conversation/id\":\"c3\"}\n"))
      (is (= [{:conversation/id "c1"}
              {:conversation/id "c2"}
              {:conversation/id "c3"}]
             (jsonl/read-records f))))))

(deftest large-text-roundtrip
  (testing "long markdown bodies with special characters round-trip"
    (let [body (str/join "\n"
                         ["# Heading"
                          ""
                          "Paragraph with \"quotes\" and \\backslash and \ttabs."
                          ""
                          "```clojure"
                          "(defn foo [x] {:bar \"baz\"})"
                          "```"
                          ""
                          (apply str (repeat 200 "Long line content. "))])
          records [{:chunk/content-markdown body :chunk/doc-num 1}]
          f (temp-file)]
      (jsonl/write-records f records)
      (is (= records (jsonl/read-records f))))))

(deftest empty-records-roundtrip
  (testing "writing zero records produces an empty file that reads back empty"
    (let [f (temp-file)]
      (jsonl/write-records f [])
      (is (= [] (jsonl/read-records f))))))
