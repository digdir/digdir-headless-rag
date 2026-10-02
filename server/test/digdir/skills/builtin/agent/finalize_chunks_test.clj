(ns digdir.skills.builtin.agent.finalize-chunks-test
  "The agent graph must PUBLISH its retrieved chunks, not leave them in the
   workspace for a consumer to dig out (#460).

   The defect this pins had two halves, and the second is easy to lose once the
   first is fixed:

   1. The outer graph declared no `:chunks` output at all, so `invoke-rag` fell
      back to `outputs → workspace-final → :chunks` — the INTERNAL map keyed by
      chunk_id. `mapv select-keys` over a map iterates `MapEntry`, and
      `select-keys` on one returns `{}`, so MCP published `[{} {}]`: the key
      present, every object empty.
   2. Even as a vector, `:title` and `:url` were nil, because the document join
      lands under a COLLECTION-NAMED key and nothing lifted it.

   Both are asserted here against the REAL finalize skill rather than a stub,
   because the two tests that already covered this area both stubbed the
   producer and both asserted a COUNT — and a count is satisfied identically by
   `[{} {}]` and by two populated chunks."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.builtin.agent.graphs :as graphs]))

(def ^:private workspace-out
  "The workspace as the loop leaves it: `:chunks` keyed by chunk_id, document
   join under a collection-named key."
  {:chunks {"c1" {:chunk_id "c1" :doc_num "7" :chunk_index 0 :retrieval-prior 0.9
                  :website_documents_ab897fbdedfa {:title "Årsrapport"
                                                   :url "https://example.test/aar"
                                                   :total_chunks 12}}
            "c2" {:chunk_id "c2" :doc_num "9" :chunk_index 0 :retrieval-prior 0.5
                  :website_documents_ab897fbdedfa {:title "Tildelingsbrev"
                                                   :total_chunks 4}}}})

(defn- finalize-outputs
  [iteration]
  (-> (graphs/execute-agent-finalize
        {:inputs {:iterations [iteration]
                  :exhausted? false
                  :ambient-ctx {:opts {:tenant "test"}}
                  :messages-init [{:role "user" :content "q"}]
                  :workspace-init {}
                  :model "test-model"
                  :temperature 0.0}})
      (skills/get-result-outputs)))

(deftest finalize-declares-chunks-as-an-output
  (testing "both outer graphs declare :chunks, so a consumer can read the top level"
    (doseq [[label graph] [["bundled" (:graph graphs/agent-rag-graph-bundled)]
                           ["faithful" (:graph graphs/agent-rag-graph-faithful)]]]
      (is (contains? (set (:outputs graph)) :chunks)
          (str label " graph must declare :chunks; without it invoke-rag falls "
               "back to the workspace map and publishes empty objects"))))
  (testing "and the step that produces it declares it too"
    (is (contains? (set (:outputs graphs/agent-finalize-metadata)) :chunks))))

(deftest finalize-publishes-populated-chunks
  (let [out (finalize-outputs {:response "Real answer."
                               :terminal-state :finalize
                               :workspace-out workspace-out})
        chunks (:chunks out)]
    (testing "the finalize step emits chunks at the top level"
      (is (some? chunks) ":chunks must be emitted, not left only on :workspace-final"))
    (testing "as a vector, not the internal chunk map"
      (is (vector? chunks))
      (is (every? map? chunks)))
    (testing "with every chunk populated"
      (is (every? seq chunks)
          "[{} {}] is the map-shaped defect and has the same count as a correct result")
      (is (= #{"c1" "c2"} (set (map :chunk_id chunks)))))
    (testing "and the joined document lifted where consumers read it"
      (let [by-id (into {} (map (juxt :chunk_id identity)) chunks)]
        (is (= "Årsrapport" (get-in by-id ["c1" :title])))
        (is (= "https://example.test/aar" (get-in by-id ["c1" :url])))
        (is (= "Tildelingsbrev" (get-in by-id ["c2" :title])))
        (is (not (contains? (get by-id "c2") :url))
            "a document without a url must stay without one, so the caller's fallback fires")))
    (testing "the internal workspace is still available for diagnostics"
      (is (map? (get-in out [:workspace-final :chunks]))
          "publishing a vector must not change how the workspace itself is stored"))))

(deftest finalize-publishes-search-attributions
  (testing "both outer graphs and the finalize step declare :search-attributions"
    (doseq [graph [(:graph graphs/agent-rag-graph-bundled) (:graph graphs/agent-rag-graph-faithful)]]
      (is (contains? (set (:outputs graph)) :search-attributions)))
    (is (contains? (set (:outputs graphs/agent-finalize-metadata)) :search-attributions)))
  (testing "the finalize step emits the workspace's attributions at the top level"
    (let [attribution {:filter-applied {:fields [{:field "type" :selected-options ["Evaluering"]}]}
                       :filter-source :explicit}]
      (is (= [attribution]
             (:search-attributions
              (finalize-outputs {:response "Real answer."
                                 :terminal-state :finalize
                                 :workspace-out (assoc workspace-out :search-attributions [attribution])})))))))
