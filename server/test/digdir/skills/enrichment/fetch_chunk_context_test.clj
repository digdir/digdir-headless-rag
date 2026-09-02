(ns digdir.skills.enrichment.fetch-chunk-context-test
  "Unit coverage for `:builtin/enrichment-fetch-chunk-context`.

   The skill is a thin shim over two Typesense lookups, so the tests
   are correspondingly thin: registration, stubbed happy path, missing
   chunk surfaces a clear ex-info, missing doc-title degrades
   gracefully."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.enrichment.fetch-chunk-context :as fcc]
            [typesense.client :as ts]))

(use-fixtures :once
  (fn [t]
    (fcc/register!)
    (t)))

(defn- stub-ts-settings [_] {:uri "http://stub" :key "k"})

(deftest skill-registered
  (testing ":builtin/enrichment-fetch-chunk-context is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-fetch-chunk-context)))))

(deftest happy-path-returns-content-and-doc-context
  (testing "Looks up chunk by chunk_id, then doc title by doc_num"
    (let [search-calls (atom [])]
      (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                    ts/search (fn [_settings coll opts]
                                (swap! search-calls conj {:coll coll :filter (:filter_by opts)})
                                (case coll
                                  "chunks" {:hits [{:document {:chunk_id "c1"
                                                               :doc_num "42"
                                                               :content_markdown "Altinn 3 ble lansert i 2020."
                                                               :url "https://example.com/page"}}]}
                                  "docs"   {:hits [{:document {:doc_num "42"
                                                               :title "Altinn 3 lansering"
                                                               :url "https://example.com/page"}}]}
                                  {:hits []}))]
        (let [res (fcc/execute-fetch-chunk-context
                   {:inputs {:tenant "digdir"
                             :chunk-id "c1"
                             :chunks-collection "chunks"
                             :docs-collection "docs"}})
              outputs (skills/get-result-outputs res)]
          (is (skills/result-success? res))
          (is (= "c1" (:chunk-id outputs)))
          (is (= "Altinn 3 ble lansert i 2020." (:chunk-content outputs)))
          (is (= "42" (:doc-num outputs)))
          (is (= "Altinn 3 lansering" (:doc-title outputs)))
          (is (= "https://example.com/page" (:doc-url outputs)))
          (is (= 2 (count @search-calls)) "Two Typesense lookups: chunks then docs"))))))

(deftest missing-doc-title-degrades-gracefully
  (testing "If docs collection doesn't return a title, the rest still works"
    (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                  ts/search (fn [_settings coll _opts]
                              (case coll
                                "chunks" {:hits [{:document {:chunk_id "c1"
                                                             :doc_num "42"
                                                             :content_markdown "..."
                                                             :url "u"}}]}
                                {:hits []}))]
      (let [res (fcc/execute-fetch-chunk-context
                 {:inputs {:tenant "digdir"
                           :chunk-id "c1"
                           :chunks-collection "chunks"
                           :docs-collection "docs"}})
            outputs (skills/get-result-outputs res)]
        (is (skills/result-success? res))
        (is (= "..." (:chunk-content outputs)))
        (is (nil? (:doc-title outputs)) "Title degrades to nil, not an error")))))

(deftest missing-chunk-throws
  (testing "If the chunk-id isn't found in Typesense, a clear ex-info bubbles up"
    (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                  ts/search (fn [& _] {:hits []})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (fcc/execute-fetch-chunk-context
                    {:inputs {:tenant "digdir"
                              :chunk-id "missing"
                              :chunks-collection "chunks"}}))))))

(deftest missing-required-inputs-throw
  (testing "Missing :chunk-id or :chunks-collection surfaces a clear ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fcc/execute-fetch-chunk-context
                  {:inputs {:tenant "digdir" :chunks-collection "chunks"}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (fcc/execute-fetch-chunk-context
                  {:inputs {:tenant "digdir" :chunk-id "c1"}})))))

(deftest tenant-falls-back-to-skill-params
  (testing "Missing :tenant input → fallback to skill-params"
    (let [observed (atom nil)]
      (with-redefs [ts-utils/make-ts-settings (fn [opts]
                                                (reset! observed (:tenant opts))
                                                {:uri "x" :key "k"})
                    ts/search (fn [& _]
                                {:hits [{:document {:chunk_id "c1"
                                                    :content_markdown "x"}}]})]
        (fcc/execute-fetch-chunk-context
         {:inputs {:chunk-id "c1" :chunks-collection "chunks"}
          :skill-params {:tenant "from-skill-params"}})
        (is (= "from-skill-params" @observed))))))
