(ns digdir.skills.builtin.agent.tools-rerank-refetch-test
  "Test that the rerank tool handler re-fetches full content for workspace
   chunks that were truncated during skim reads."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.skills.builtin.agent.tools :as tools]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.rag.core :as rag]
            [digdir.rag.skills.core :as skills]))

(def ^:private full-content-a
  "This is the FULL content of chunk A with all the important details about subscribing to events.")

(def ^:private full-content-b
  "This is the FULL content of chunk B explaining webhook configuration and setup.")

(defn- make-truncated-chunk [chunk-id content-prefix]
  {:chunk_id chunk-id
   :doc_num "doc-1"
   :chunk_index 0
   :content_markdown (str content-prefix "\n[truncated]")
   :hit-count 1
   :original-rank 1.0})

(defn- make-full-chunk [chunk-id content]
  {:chunk_id chunk-id
   :doc_num "doc-1"
   :chunk_index 0
   :content_markdown content})

(deftest rerank-refetches-truncated-workspace-chunks
  (testing "Workspace chunks ending with [truncated] are re-fetched with full content"
    (let [!workspace (workspace/create-workspace)
          ;; Pre-populate workspace with truncated chunks (as if read with max_content_length)
          truncated-a (make-truncated-chunk "chunk-a" "This is the")
          truncated-b (make-truncated-chunk "chunk-b" "This is the")
          non-truncated-c (make-full-chunk "chunk-c" "Already has full content.")
          refetch-calls (atom [])
          rerank-inputs (atom nil)]

      ;; Add truncated + non-truncated chunks to workspace
      (workspace/add-chunks-to-workspace! !workspace [truncated-a truncated-b non-truncated-c])

      ;; Verify workspace has truncated content
      (is (= "This is the\n[truncated]"
             (get-in @!workspace [:chunks "chunk-a" :content_markdown])))

      (with-redefs [;; Stub retrieve-chunks-by-id to return full content
                    rag/retrieve-chunks-by-id
                    (fn [_docs _chunks chunk-id-list opts]
                      (swap! refetch-calls conj {:chunk-ids (mapv :chunk_id chunk-id-list)
                                                  :opts opts})
                      ;; Return full content for the requested chunks
                      [(make-full-chunk "chunk-a" full-content-a)
                       (make-full-chunk "chunk-b" full-content-b)])

                    ;; Stub execute-sub-skill to capture the chunks it receives
                    tools/execute-sub-skill
                    (fn [skill-id inputs _opts & _]
                      (when (= :builtin/rerank skill-id)
                        (reset! rerank-inputs inputs))
                      (skills/success-result
                       {:chunks (:chunks inputs)
                        :context-docs (mapv (fn [c]
                                              {:page_content (:content_markdown c)
                                               :metadata {:source (:chunk_id c)}})
                                            (:chunks inputs))}
                       {}))]

        (tools/execute-tool-call
         "rerank_results"
         {:query "test query"}
         !workspace
         {:docs-collection "docs"
          :chunks-collection "chunks"
          :opts {:tenant "test" :dataset-config-key "test-key"
                 :skill-params {}}}))

      ;; 1. Should have re-fetched only the truncated chunks
      (is (= 1 (count @refetch-calls))
          "Should make exactly one refetch call")
      (is (= #{"chunk-a" "chunk-b"}
             (set (:chunk-ids (first @refetch-calls))))
          "Should refetch only the truncated chunk IDs")

      ;; 2. Reranker should have received full content
      (let [rerank-chunks (:chunks @rerank-inputs)
            by-id (into {} (map (juxt :chunk_id :content_markdown) rerank-chunks))]
        (is (= full-content-a (get by-id "chunk-a"))
            "Reranker should see full content for chunk-a")
        (is (= full-content-b (get by-id "chunk-b"))
            "Reranker should see full content for chunk-b")
        (is (= "Already has full content." (get by-id "chunk-c"))
            "Non-truncated chunk should be unchanged"))

      ;; 3. Workspace should be updated with full content
      (is (= full-content-a
             (get-in @!workspace [:chunks "chunk-a" :content_markdown]))
          "Workspace should be updated with full content for chunk-a")
      (is (= full-content-b
             (get-in @!workspace [:chunks "chunk-b" :content_markdown]))
          "Workspace should be updated with full content for chunk-b"))))

(deftest rerank-skips-refetch-when-no-truncation
  (testing "No refetch happens when workspace chunks are not truncated"
    (let [!workspace (workspace/create-workspace)
          chunk-a (make-full-chunk "chunk-a" "Full content here.")
          refetch-calls (atom [])]

      (workspace/add-chunks-to-workspace! !workspace [chunk-a])

      (with-redefs [rag/retrieve-chunks-by-id
                    (fn [& _args]
                      (swap! refetch-calls conj true)
                      [])

                    tools/execute-sub-skill
                    (fn [_skill-id inputs _opts & _]
                      (skills/success-result
                       {:chunks (:chunks inputs)
                        :context-docs []}
                       {}))]

        (tools/execute-tool-call
         "rerank_results"
         {:query "test query"}
         !workspace
         {:docs-collection "docs"
          :chunks-collection "chunks"
          :opts {:tenant "test" :dataset-config-key "test-key"
                 :skill-params {}}}))

      (is (empty? @refetch-calls)
          "Should not call retrieve-chunks-by-id when nothing is truncated"))))
