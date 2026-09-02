(ns digdir.rag.core-test
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.config.accessor :as cfg]
            [digdir.rag.core :as rag]))

(deftest core-facade-exposes-only-supported-query-relaxation-api
  (testing "The facade keeps query-relaxation internals private to the dedicated namespace"
    (let [publics (set (keys (ns-publics 'digdir.rag.core)))]
      (is (contains? publics 'query-relaxation))
      (is (not (contains? publics 'do-query-relaxation)))
      (is (not (contains? publics 'search-results-tools)))
      (is (not (contains? publics 'prepare-conversation))))))

(deftest rerank-chunks-skips-colbert-on-empty-input
  (testing "Empty rerank inputs return an empty result without calling ColBERT"
    (let [http-called? (atom false)
          result (with-redefs [cfg/get (fn [_opts & path]
                                         (case (vec path)
                                           [:services :colbert :api-url] "https://colbert.example.test/rerank"
                                           [:services :colbert :api-key] "test-key"
                                           nil))
                               http/post (fn [& _]
                                           (reset! http-called? true)
                                           (throw (ex-info "http/post should not be called" {})))]
                   (rag/rerank-chunks
                    []
                    {:translated_user_query "kan jeg bruke det til å lage digitale tjenester?"
                     :docsCollectionName "docs"
                     :promptRagGenerate "Context:\n{context}\n\nQuestion:\n{question}"
                     :rerankTopkChunks 10
                     :rerankMaxChunkLength 1000
                     :rerankMaxLength 10000
                     :contextTopkChunks 10
                     :maxContextLength 8000}))]
      (is (false? @http-called?))
      (is (= [] (:reranked-chunks result)))
      (is (= [] (:used-chunks result)))
      (is (= [] (:used-docs result)))
      (is (string? (:full-prompt result)))
      (is (str/includes? (:full-prompt result) "kan jeg bruke det til å lage digitale tjenester?"))
      (is (not (str/includes? (:full-prompt result) "{context}")))
      (is (not (str/includes? (:full-prompt result) "{question}"))))))
