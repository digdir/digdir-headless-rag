(ns digdir.skills.enrichment.revert-chunk-test
  "Unit coverage for `:builtin/enrichment-revert-chunk`.

   Like the sibling apply-questions tests, we stub Typesense via
   `with-redefs` — the goal here is to pin the filter shape, the
   delete-call signature, the 404 idempotency path, and the input
   validation. End-to-end behavior against a live collection is
   covered by the Phase B.5 / graph smoke loop."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.enrichment.revert-chunk :as rc]
            [typesense.client :as ts]))

(use-fixtures :once
  (fn [t]
    (rc/register!)
    (t)))

(deftest skill-registered
  (testing ":builtin/enrichment-revert-chunk is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-revert-chunk)))))

(deftest build-filter-by-shape
  (testing "Without prompt-hash: chunk_id bracket-list (one element)"
    (is (= "chunk_id:=[c1]" (rc/build-filter-by "c1" nil)))
    (is (= "chunk_id:=[c1]" (rc/build-filter-by "c1" ""))))
  (testing "With prompt-hash: AND clause, both fields in bracket-list form"
    (is (= "chunk_id:=[c1] && prompt_hash:=[abc123]"
           (rc/build-filter-by "c1" "abc123")))))

(deftest dry-run-does-not-touch-typesense
  (testing "With :dry-run? true, returns the planned filter and makes no Typesense calls"
    (let [calls (atom [])]
      (with-redefs [ts-utils/make-ts-settings (fn [_]
                                                (swap! calls conj :make-settings)
                                                {:uri "http://stub" :key "k"})
                    ts/delete-documents! (fn [& args]
                                           (swap! calls conj [:delete args]))]
        (let [res (rc/execute-revert-chunk
                   {:inputs {:chunk-id "c1"
                             :collection-name "enrichment_hypothetical_questions_abc"
                             :prompt-hash "abc123"}
                    :parameters {:dry-run? true}
                    :skill-params {:tenant "digdir"}})
              outputs (skills/get-result-outputs res)]
          (is (skills/result-success? res))
          (is (= 0 (:reverted-count outputs)))
          (is (= "c1" (:chunk-id outputs)))
          (is (= "chunk_id:=[c1] && prompt_hash:=[abc123]" (:filter-by outputs)))
          (is (empty? @calls) "dry-run? must not touch Typesense at all"))))))

(deftest live-path-calls-delete-with-filter
  (testing "Without dry-run?, deletes by filter and returns Typesense num_deleted"
    (let [calls (atom [])]
      (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "http://stub" :key "k"})
                    ts/delete-documents! (fn [_settings coll opts]
                                           (swap! calls conj
                                                  {:op :delete
                                                   :coll coll
                                                   :filter (:filter_by opts)})
                                           {:num_deleted 4})]
        (let [res (rc/execute-revert-chunk
                   {:inputs {:chunk-id "c1"
                             :collection-name "enrichment_hypothetical_questions_abc"}
                    :parameters {}
                    :skill-params {:tenant "digdir"}})
              outputs (skills/get-result-outputs res)
              ops @calls]
          (is (skills/result-success? res))
          (is (= 4 (:reverted-count outputs)))
          (is (= 1 (count ops)))
          (is (= :delete (-> ops first :op)))
          (is (= "enrichment_hypothetical_questions_abc" (-> ops first :coll)))
          (is (= "chunk_id:=[c1]" (-> ops first :filter))))))))

(deftest live-path-narrows-by-prompt-hash-when-supplied
  (testing "Supplying :prompt-hash narrows the filter to one specific propose's rows"
    (let [observed-filter (atom nil)]
      (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "http://stub" :key "k"})
                    ts/delete-documents! (fn [_settings _coll opts]
                                           (reset! observed-filter (:filter_by opts))
                                           {:num_deleted 4})]
        (let [res (rc/execute-revert-chunk
                   {:inputs {:chunk-id "c1"
                             :collection-name "enrichment_hypothetical_questions_abc"
                             :prompt-hash "ph-abc"}
                    :parameters {}
                    :skill-params {:tenant "digdir"}})]
          (is (skills/result-success? res))
          (is (= "chunk_id:=[c1] && prompt_hash:=[ph-abc]" @observed-filter)
              "Filter must AND prompt_hash so other proposes' rows are untouched"))))))

(deftest outputs-carry-decision-revert
  (testing "Both dry-run and live paths include :decision :revert so the graph's :select can collect uniform outcomes"
    (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "x" :key "k"})
                  ts/delete-documents! (fn [& _] {:num_deleted 2})]
      (let [dry (rc/execute-revert-chunk
                 {:inputs {:chunk-id "c1"
                           :collection-name "enrichment_hypothetical_questions_abc"}
                  :parameters {:dry-run? true}
                  :skill-params {:tenant "digdir"}})
            live (rc/execute-revert-chunk
                  {:inputs {:chunk-id "c1"
                            :collection-name "enrichment_hypothetical_questions_abc"}
                   :parameters {}
                   :skill-params {:tenant "digdir"}})]
        (is (= :revert (:decision (skills/get-result-outputs dry))))
        (is (= :revert (:decision (skills/get-result-outputs live))))))))

(deftest outputs-echo-proposal-eval-verify-when-supplied
  (testing "D2.13 — proposal/eval/verify pass-throughs land in outputs so the report can render them"
    (let [proposal {:chunk-id "c1"
                    :phrases ["Altinn 3 lansering" "Altinn 3 juni 2020"]
                    :provenance {:model "gpt-4o"}}
          eval' {:gate-pass true :cases 1 :current-pass 1 :relaxed-pass 1}
          verify {:improved? false
                  :enriched-found? true
                  :baseline-found? true
                  :position-delta -3
                  :matched-enrichment {:phrase "Altinn 3 lansering"}
                  :keep? false}]
      (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "x" :key "k"})
                    ts/delete-documents! (fn [& _] {:num_deleted 5})]
        (let [res (rc/execute-revert-chunk
                   {:inputs {:chunk-id "c1"
                             :collection-name "enrich"
                             :proposal proposal
                             :eval eval'
                             :verify verify}
                    :parameters {}
                    :skill-params {:tenant "digdir"}})
              outputs (skills/get-result-outputs res)]
          (is (= proposal (:proposal outputs))
              "proposal echoed verbatim")
          (is (= eval' (:eval outputs))
              "eval echoed verbatim")
          (is (= verify (:verify outputs))
              "verify echoed verbatim")
          (is (= :revert (:decision outputs)))
          (is (= 5 (:reverted-count outputs))))))))

(deftest outputs-omit-pass-through-keys-when-absent
  (testing "Without proposal/eval/verify inputs, those keys do NOT appear in outputs (avoids nil noise)"
    (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "x" :key "k"})
                  ts/delete-documents! (fn [& _] {:num_deleted 0})]
      (let [res (rc/execute-revert-chunk
                 {:inputs {:chunk-id "c1" :collection-name "x"}
                  :parameters {}
                  :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs res)]
        (is (not (contains? outputs :proposal)))
        (is (not (contains? outputs :eval)))
        (is (not (contains? outputs :verify)))))))

(deftest typesense-404-is-idempotent-no-op
  (testing "A 404 'no documents matched' becomes reverted-count 0, not a thrown error"
    (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "http://stub" :key "k"})
                  ts/delete-documents! (fn [& _]
                                         (throw (ex-info "No documents matched"
                                                         {:status 404})))]
      (let [res (rc/execute-revert-chunk
                 {:inputs {:chunk-id "c1"
                           :collection-name "enrichment_hypothetical_questions_abc"}
                  :parameters {}
                  :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs res)]
        (is (skills/result-success? res))
        (is (= 0 (:reverted-count outputs))
            "404 is a successful no-op for an already-empty chunk")))))

(deftest non-404-typesense-errors-propagate
  (testing "Other ExceptionInfo errors are NOT swallowed"
    (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "http://stub" :key "k"})
                  ts/delete-documents! (fn [& _]
                                         (throw (ex-info "Server exploded"
                                                         {:status 500})))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (rc/execute-revert-chunk
                    {:inputs {:chunk-id "c1"
                              :collection-name "enrichment_hypothetical_questions_abc"}
                     :parameters {}
                     :skill-params {:tenant "digdir"}}))))))

(deftest missing-collection-name-throws
  (testing "An empty or nil :collection-name surfaces a clear ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (rc/execute-revert-chunk
                  {:inputs {:chunk-id "c1" :collection-name nil}
                   :parameters {}
                   :skill-params {:tenant "digdir"}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rc/execute-revert-chunk
                  {:inputs {:chunk-id "c1" :collection-name ""}
                   :parameters {}
                   :skill-params {:tenant "digdir"}})))))

(deftest missing-chunk-id-throws
  (testing "An empty or nil :chunk-id surfaces a clear ex-info — refusing to delete the whole collection"
    (is (thrown? clojure.lang.ExceptionInfo
                 (rc/execute-revert-chunk
                  {:inputs {:chunk-id nil
                            :collection-name "enrichment_hypothetical_questions_abc"}
                   :parameters {}
                   :skill-params {:tenant "digdir"}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rc/execute-revert-chunk
                  {:inputs {:chunk-id ""
                            :collection-name "enrichment_hypothetical_questions_abc"}
                   :parameters {}
                   :skill-params {:tenant "digdir"}})))))
