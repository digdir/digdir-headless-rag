(ns digdir.setup.llm-test
  "Covers the pure parts of the setup wizard's LLM-provider section.

   The writes themselves need a config DB and are exercised by running
   `bb setup`; what is worth pinning here is the logic that decides WHICH
   tenants and WHICH model the section offers, because both have a wrong
   answer that looks plausible: `__global__` has no platform `default` node,
   and an embedding model appears in `/v1/models` next to the chat ones."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.setup.llm :as setup-llm]))

(deftest real-tenants-test
  (testing "the two internal pseudo-tenants are not offered as choices"
    (is (= ["digdir" "public-sector-knowledge"]
           (setup-llm/real-tenants
            ["__global__" "__platform-defaults__" "digdir" "public-sector-knowledge"]))))

  (testing "order is preserved and a list with no real tenants comes back empty"
    (is (= ["b" "a"] (setup-llm/real-tenants ["b" "__global__" "a"])))
    (is (= [] (setup-llm/real-tenants ["__global__" "__platform-defaults__"])))
    (is (= [] (setup-llm/real-tenants [])))))

(deftest embedding-model?-test
  (testing "the ids a local server lists alongside its chat models are excluded"
    (is (true? (setup-llm/embedding-model? "text-embedding-nomic-embed-text-v1.5")))
    (is (true? (setup-llm/embedding-model? "text-embedding-snowflake-arctic-embed-l-v2.0")))
    (is (true? (setup-llm/embedding-model? "BAAI/bge-reranker-v2-m3"))))

  (testing "chat models are not"
    (is (false? (setup-llm/embedding-model? "qwen/qwen3-8b")))
    (is (false? (setup-llm/embedding-model? "llama-3.3-70b-instruct")))
    (is (false? (setup-llm/embedding-model? nil)))))

(deftest probe-models-never-throws-test
  (testing "an endpoint nothing is listening on is an ordinary answer, not a throw"
    ;; The wizard runs before `bb dev`, so the local server being down is the
    ;; common case, not an error case. A throw here would abort the section.
    (let [{:keys [ok? error]} (setup-llm/probe-models "http://127.0.0.1:1/v1")]
      (is (false? ok?))
      (is (string? error))))

  (testing "a syntactically impossible URL is reported, not raised"
    (let [{:keys [ok? error]} (setup-llm/probe-models "not-a-url")]
      (is (false? ok?))
      (is (string? error)))))

(deftest default-endpoint-test
  (testing "the offered default is LM Studio's, base URL including /v1"
    (is (= "http://localhost:1234/v1" setup-llm/default-local-endpoint))))
