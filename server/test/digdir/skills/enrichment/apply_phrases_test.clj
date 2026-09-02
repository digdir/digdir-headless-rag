(ns digdir.skills.enrichment.apply-phrases-test
  "Phase D1 — unit coverage for :builtin/enrichment-apply-phrases.

   Mirrors apply-questions-test (same shape, different field). The
   row-failure surfacing test is the one that catches regressions on
   the silent-success bug we hit during Phase C.5 validation; the
   doc-num backfill test catches the other validation-time bug."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.enrichment.apply-phrases :as ap]
            [typesense.client :as ts]))

(use-fixtures :once
  (fn [t]
    (ap/register!)
    (t)))

(def ^:private sample-proposals
  [{:chunk-id "c1"
    :doc-num "42"
    :phrases ["Altinn 3 lansering"
              "Altinn 3 juni 2020"]
    :provenance {:model "gpt-4o"
                 :prompt-hash "abc123"
                 :generated-at-ms 1700000000000}}
   {:chunk-id "c2"
    :doc-num "43"
    :phrases ["Altinn Studio"
              ""              ;; should be dropped
              nil]            ;; should be dropped
    :provenance {:model "gpt-4o"
                 :prompt-hash "abc123"
                 :generated-at-ms 1700000000000}}])

(deftest skill-registered
  (testing ":builtin/enrichment-apply-phrases is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-apply-phrases)))))

(deftest proposals-to-rows-flattens-and-drops-blanks
  (let [rows (ap/proposals->rows sample-proposals)]
    (testing "Each row carries chunk_id, doc_num, phrase, and provenance fields"
      (is (= 3 (count rows)) "2 from c1 + 1 from c2 (blanks dropped)")
      (is (every? :chunk_id rows))
      (is (every? :doc_num rows))
      (is (every? :phrase rows))
      (is (every? :model rows))
      (is (every? :prompt_hash rows))
      (is (every? :generated_at rows)))))

(deftest chunk-ids-filter-uses-bracket-list
  (testing "Filter clause matches Typesense bracket-list syntax (lesson learned from revert-chunk)"
    (is (= "chunk_id:=[a,b,c]" (ap/chunk-ids-filter ["a" "b" "a" "c"]))))
  (testing "Empty input returns nil so the delete call can be skipped"
    (is (nil? (ap/chunk-ids-filter [])))
    (is (nil? (ap/chunk-ids-filter [nil nil])))))

(deftest dry-run-does-not-touch-typesense
  (testing "With :dry-run? true, the skill returns planned rows and never calls Typesense"
    (let [calls (atom [])]
      (with-redefs [ts-utils/make-ts-settings (fn [_]
                                                (swap! calls conj :make-settings)
                                                {:uri "http://stub" :key "k"})
                    ts/delete-documents! (fn [& args]
                                           (swap! calls conj [:delete args]))
                    ts/upsert-documents! (fn [& args]
                                           (swap! calls conj [:upsert args]))]
        (let [res (ap/execute-apply-phrases
                   {:inputs {:proposals sample-proposals
                             :collection-name "enrichment_verified_phrases_abc"}
                    :parameters {:dry-run? true}
                    :skill-params {:tenant "digdir"}})
              outputs (skills/get-result-outputs res)]
          (is (skills/result-success? res))
          (is (= 3 (:applied-count outputs)))
          (is (= 3 (count (:rows outputs))))
          (is (empty? @calls) "dry-run? must not touch Typesense"))))))

(deftest live-path-delete-then-upsert
  (testing "Without dry-run?, deletes by filter then upserts, counts real successes from Typesense response"
    (let [calls (atom [])]
      (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "x" :key "k"})
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
                                           (mapv (fn [_] {:success true}) docs))]
        (let [res (ap/execute-apply-phrases
                   {:inputs {:proposals sample-proposals
                             :collection-name "enrichment_verified_phrases_abc"}
                    :parameters {}
                    :skill-params {:tenant "digdir"}})
              outputs (skills/get-result-outputs res)
              ops @calls]
          (is (skills/result-success? res))
          (is (= 3 (:applied-count outputs)))
          (is (= [:delete :upsert] (mapv :op ops))
              "Delete must come before upsert")
          (is (= "chunk_id:=[c1,c2]" (:filter (first ops)))))))))

(deftest upsert-row-failures-surface-as-exception
  (testing "If Typesense rejects any row, apply-phrases throws — must not silently report success"
    (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "x" :key "k"})
                  ts/delete-documents! (fn [& _] {:num_deleted 0})
                  ts/upsert-documents! (fn [_settings _coll docs]
                                         (mapv (fn [_]
                                                 {:success false
                                                  :code 400
                                                  :error "Error with field `doc_num`: Value cannot be empty."})
                                               docs))]
      (let [thrown (try
                     (ap/execute-apply-phrases
                      {:inputs {:proposals sample-proposals
                                :collection-name "enrichment_verified_phrases_abc"}
                       :parameters {}
                       :skill-params {:tenant "digdir"}})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (some? thrown) "Must throw, not silently succeed")
        (is (= :builtin/enrichment-apply-phrases
               (-> thrown ex-data :skill-id)))
        (is (= 3 (-> thrown ex-data :failed-count)))
        (is (re-find #"doc_num" (-> thrown ex-data :first-error)))))))

(deftest singular-proposal-with-doc-num-backfill
  (testing "Graph callers pass `:proposal` (singular map) without :doc-num; separate :doc-num input backfills"
    (let [observed-docs (atom nil)]
      (with-redefs [ts-utils/make-ts-settings (fn [_] {:uri "x" :key "k"})
                    ts/delete-documents! (fn [& _] {:num_deleted 0})
                    ts/upsert-documents! (fn [_settings _coll docs]
                                           (reset! observed-docs docs)
                                           (mapv (fn [_] {:success true}) docs))]
        (let [res (ap/execute-apply-phrases
                   {:inputs {:proposal {:chunk-id "c1"
                                        :phrases ["p1" "p2"]
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
  (testing "An empty :collection-name surfaces a clear ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ap/execute-apply-phrases
                  {:inputs {:proposals sample-proposals :collection-name nil}
                   :parameters {}
                   :skill-params {:tenant "digdir"}})))))
