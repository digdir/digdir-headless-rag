(ns digdir.skills.enrichment.apply-questions-test
  "Phase B.3 — unit coverage for :builtin/enrichment-apply-questions.

   Like the propose-questions tests, we don't hit a real Typesense in
   here. The Phase B.5 eval-gate run is what proves end-to-end behavior.
   These tests pin the wiring that, if it breaks silently, leaves the
   enrichment collection inconsistent:

   1. Skill is registered.
   2. proposals→rows flattens correctly and stamps provenance onto each
      row, dropping blank/empty questions.
   3. chunk-ids-filter builds a syntactically valid Typesense filter,
      and returns nil on an empty input so callers can short-circuit
      the delete call.
   4. The skill body honors :dry-run? without touching Typesense.
   5. The live path calls delete-documents! then upsert-documents! in
      that order, with the expected filter."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.apply-questions :as aq]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts]))

(use-fixtures :once
  (fn [t]
    (aq/register!)
    (t)))

(def ^:private sample-proposals
  [{:chunk-id "c1"
    :doc-num "42"
    :questions ["Når ble Altinn 3 lansert?"
                "Hva er Altinn 3?"]
    :provenance {:model "gpt-4o"
                 :prompt-hash "abc123"
                 :generated-at-ms 1700000000000}}
   {:chunk-id "c2"
    :doc-num "43"
    :questions ["Hvem kan bruke Altinn?"
                ""              ;; should be dropped
                nil]            ;; should be dropped
    :provenance {:model "gpt-4o"
                 :prompt-hash "abc123"
                 :generated-at-ms 1700000000000}}])

(deftest skill-registered
  (testing ":builtin/enrichment-apply-questions is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-apply-questions)))))

(deftest proposals-to-rows-flattens-and-drops-blanks
  (let [rows (aq/proposals->rows sample-proposals)]
    (testing "Each row carries chunk_id, doc_num, question, and provenance fields"
      (is (= 3 (count rows)) "2 from c1 + 1 from c2 (blanks dropped)")
      (is (every? :chunk_id rows))
      (is (every? :doc_num rows))
      (is (every? :question rows))
      (is (every? :model rows))
      (is (every? :prompt_hash rows))
      (is (every? :generated_at rows)))
    (testing "doc_num is stringified to match Typesense schema"
      (is (every? string? (map :doc_num rows))))))

(deftest proposals-to-rows-auto-stamps-missing-provenance
  (testing "Inline-composed proposals (no provenance) still get :model and :generated_at"
    (let [before (System/currentTimeMillis)
          rows (aq/proposals->rows
                [{:chunk-id "c1"
                  :doc-num "42"
                  :questions ["q1"]
                  :provenance {}}])
          row (first rows)
          after (System/currentTimeMillis)]
      (is (= 1 (count rows)))
      (is (= "agent-composed" (:model row))
          ":model defaults to 'agent-composed' when caller didn't stamp one")
      (is (and (<= before (:generated_at row))
               (<= (:generated_at row) after))
          ":generated_at is stamped to now-ish")
      (is (false? (contains? row :prompt_hash))
          ":prompt_hash stays absent when we don't have one to stamp"))))

(deftest proposals-to-rows-preserves-explicit-provenance
  (testing "When proposals carry full provenance, those values win over the auto-stamp defaults"
    (let [rows (aq/proposals->rows
                [{:chunk-id "c1"
                  :doc-num "42"
                  :questions ["q1"]
                  :provenance {:model "gpt-stub"
                               :prompt-hash "abc123"
                               :generated-at-ms 1700000000000}}])
          row (first rows)]
      (is (= "gpt-stub" (:model row)))
      (is (= "abc123" (:prompt_hash row)))
      (is (= 1700000000000 (:generated_at row))))))

(deftest chunk-ids-filter-shape
  (testing "Filter clause matches Typesense bracket-list syntax and dedupes"
    (is (= "chunk_id:=[a,b,c]" (aq/chunk-ids-filter ["a" "b" "a" "c"]))))
  (testing "Empty / all-nil input returns nil so the delete call can be skipped"
    (is (nil? (aq/chunk-ids-filter [])))
    (is (nil? (aq/chunk-ids-filter [nil nil])))))

(deftest dry-run-does-not-touch-typesense
  (testing "With :dry-run? true, the skill returns the planned rows and never calls Typesense"
    (let [calls (atom [])]
      (with-redefs [ts-utils/make-ts-settings (fn [_]
                                                (swap! calls conj :make-settings)
                                                {:uri "http://stub" :key "k"})
                    ts/delete-documents! (fn [& args]
                                           (swap! calls conj [:delete args]))
                    ts/upsert-documents! (fn [& args]
                                           (swap! calls conj [:upsert args]))]
        (let [res (aq/execute-apply-questions
                   {:inputs {:proposals sample-proposals
                             :collection-name "enrichment_hypothetical_questions_abc"}
                    :parameters {:dry-run? true}
                    :skill-params {:tenant "digdir"}})
              outputs (skills/get-result-outputs res)]
          (is (skills/result-success? res))
          (is (= 3 (:applied-count outputs)))
          (is (= ["c1" "c2"] (:chunk-ids outputs)))
          (is (= 3 (count (:rows outputs))))
          (is (empty? @calls) "dry-run? must not touch Typesense at all"))))))

(deftest live-path-calls-delete-then-upsert
  (testing "Without dry-run?, the skill deletes by filter on chunk-ids then upserts"
    (let [calls (atom [])]
      (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "http://stub" :key "k"})
                    ts/delete-documents! (fn [_settings coll opts]
                                           (swap! calls conj
                                                  {:op :delete
                                                   :coll coll
                                                   :filter (:filter_by opts)})
                                           {:num_deleted 0})
                    ts/upsert-documents! (fn [_settings coll docs]
                                           (swap! calls conj
                                                  {:op :upsert
                                                   :coll coll
                                                   :count (count docs)})
                                           ;; Typesense returns a per-row
                                           ;; vec with `:success` flags;
                                           ;; apply-questions now counts
                                           ;; the successes rather than
                                           ;; trusting the input count.
                                           (mapv (fn [_] {:success true}) docs))]
        (let [res (aq/execute-apply-questions
                   {:inputs {:proposals sample-proposals
                             :collection-name "enrichment_hypothetical_questions_abc"}
                    :parameters {}
                    :skill-params {:tenant "digdir"}})
              outputs (skills/get-result-outputs res)
              ops @calls]
          (is (skills/result-success? res))
          (is (= 3 (:applied-count outputs)))
          (is (= [:delete :upsert] (mapv :op ops))
              "Delete must come before upsert")
          (is (= "chunk_id:=[c1,c2]" (:filter (first ops))))
          (is (= 3 (:count (second ops))))
          (is (every? #{"enrichment_hypothetical_questions_abc"}
                      (map :coll ops))))))))

(deftest upsert-row-failures-surface-as-exception
  (testing "If Typesense rejects any row, apply-questions throws — must NOT silently report applied-count=(count rows)"
    (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "x" :key "k"})
                  ts/delete-documents! (fn [& _] {:num_deleted 0})
                  ts/upsert-documents! (fn [_settings _coll docs]
                                         ;; Simulate the real bug: every row
                                         ;; rejected with the empty-doc_num
                                         ;; error.
                                         (mapv (fn [_]
                                                 {:success false
                                                  :code 400
                                                  :error "Error with field `doc_num`: Value cannot be empty."})
                                               docs))]
      (let [thrown (try
                     (aq/execute-apply-questions
                      {:inputs {:proposals sample-proposals
                                :collection-name "enrichment_hypothetical_questions_abc"}
                       :parameters {}
                       :skill-params {:tenant "digdir"}})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown) "Must throw, not silently succeed")
        (is (= :builtin/enrichment-apply-questions
               (-> thrown ex-data :skill-id)))
        (is (= 3 (-> thrown ex-data :failed-count))
            "All three sample rows should be counted as failed")
        (is (re-find #"doc_num" (-> thrown ex-data :first-error))
            "First-error message preserved for debugging")))))

(deftest singular-proposal-with-doc-num-backfill
  (testing "Graph callers pass `:proposal` (singular map) without `:doc-num`; the separate `:doc-num` input backfills it"
    (let [observed-docs (atom nil)]
      (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "x" :key "k"})
                    ts/delete-documents! (fn [& _] {:num_deleted 0})
                    ts/upsert-documents! (fn [_settings _coll docs]
                                           (reset! observed-docs docs)
                                           (mapv (fn [_] {:success true}) docs))]
        (let [res (aq/execute-apply-questions
                   {:inputs {:proposal {:chunk-id "c1"
                                        :questions ["q1" "q2"]
                                        :provenance {:model "m" :prompt-hash "h"}}
                             :doc-num "42"
                             :collection-name "x"}
                    :parameters {}
                    :skill-params {:tenant "digdir"}})
              outputs (skills/get-result-outputs res)]
          (is (skills/result-success? res))
          (is (= 2 (:applied-count outputs)))
          (is (every? #(= "42" (:doc_num %)) @observed-docs)
              "Every upserted row has the backfilled doc_num"))))))

(deftest missing-collection-name-throws
  (testing "An empty :collection-name surfaces a clear ex-info, not a silent Typesense failure"
    (is (thrown? clojure.lang.ExceptionInfo
                 (aq/execute-apply-questions
                  {:inputs {:proposals sample-proposals :collection-name nil}
                   :parameters {}
                   :skill-params {:tenant "digdir"}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (aq/execute-apply-questions
                  {:inputs {:proposals sample-proposals :collection-name ""}
                   :parameters {}
                   :skill-params {:tenant "digdir"}})))))

(deftest empty-proposals-short-circuits
  (testing "No work to do → no Typesense calls, success result with applied-count 0"
    (let [calls (atom [])]
      (with-redefs [ts-utils/make-ts-settings (fn [_] (swap! calls conj :make) {:uri "x" :key "k"})
                    ts/delete-documents! (fn [& _] (swap! calls conj :delete))
                    ts/upsert-documents! (fn [& _] (swap! calls conj :upsert))]
        (let [res (aq/execute-apply-questions
                   {:inputs {:proposals [] :collection-name "x"}
                    :parameters {}
                    :skill-params {:tenant "digdir"}})
              outputs (skills/get-result-outputs res)]
          (is (skills/result-success? res))
          (is (= 0 (:applied-count outputs)))
          (is (empty? @calls)))))))
