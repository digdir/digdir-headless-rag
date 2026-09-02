(ns digdir.skills.builtin.agent.workspace-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.skills.builtin.agent.workspace :as workspace]))

(defn- workspace-with-chunks
  [chunks]
  {:chunks (zipmap (map :chunk_id chunks) chunks)
   :refunded-chunk-ids #{}
   :read-content-length (reduce + (map :content_length chunks))})

(deftest compute-budget-refund-all-chunks-supported
  (testing "Refund is empty when every read chunk is referenced in supported-claims"
    (let [ws (workspace-with-chunks [{:chunk_id "c1" :content_length 1000}
                                     {:chunk_id "c2" :content_length 2000}])
          signal {:confidence 0.8
                  :degraded? false
                  :status :support-found
                  :chunk-ids ["c1" "c2"]
                  :supported-claims [{:claim-id :topic-match :chunk-ids ["c1" "c2"]}]}
          {:keys [refunded-ids refunded-chars]} (workspace/compute-budget-refund ws signal)]
      (is (= [] refunded-ids))
      (is (= 0 refunded-chars)))))

(deftest compute-budget-refund-gated-on-low-confidence
  (testing "No refund when confidence < 0.5, even if no supported claims"
    (let [ws (workspace-with-chunks [{:chunk_id "c1" :content_length 1500}])
          signal {:confidence 0.0
                  :degraded? false
                  :status :no-support
                  :chunk-ids ["c1"]
                  :supported-claims []}
          {:keys [refunded-ids refunded-chars]} (workspace/compute-budget-refund ws signal)]
      (is (= [] refunded-ids))
      (is (= 0 refunded-chars)))))

(deftest compute-budget-refund-gated-on-degraded
  (testing "No refund when read-signal is degraded"
    (let [ws (workspace-with-chunks [{:chunk_id "c1" :content_length 1500}])
          signal {:confidence 0.9
                  :degraded? true
                  :status :unclear
                  :chunk-ids ["c1"]
                  :supported-claims []}
          {:keys [refunded-ids refunded-chars]} (workspace/compute-budget-refund ws signal)]
      (is (= [] refunded-ids))
      (is (= 0 refunded-chars)))))

(deftest compute-budget-refund-gated-on-unclear-status
  (testing "No refund when status is :unclear"
    (let [ws (workspace-with-chunks [{:chunk_id "c1" :content_length 1500}])
          signal {:confidence 0.9
                  :degraded? false
                  :status :unclear
                  :chunk-ids ["c1"]
                  :supported-claims []}
          {:keys [refunded-ids refunded-chars]} (workspace/compute-budget-refund ws signal)]
      (is (= [] refunded-ids))
      (is (= 0 refunded-chars)))))

(deftest compute-budget-refund-partial-unsupported
  (testing "Refunds only unsupported chunks when confidence is high enough"
    (let [ws (workspace-with-chunks [{:chunk_id "c1" :content_length 1000}
                                     {:chunk_id "c2" :content_length 2000}
                                     {:chunk_id "c3" :content_length 3000}])
          signal {:confidence 0.7
                  :degraded? false
                  :status :support-found
                  :chunk-ids ["c1" "c2" "c3"]
                  :supported-claims [{:claim-id :topic-match :chunk-ids ["c1"]}]}
          {:keys [refunded-ids refunded-chars]} (workspace/compute-budget-refund ws signal)]
      (is (= #{"c2" "c3"} (set refunded-ids)))
      (is (= 5000 refunded-chars)))))

(deftest compute-budget-refund-skips-already-refunded
  (testing "Chunks already in :refunded-chunk-ids are not double-refunded"
    (let [ws (-> (workspace-with-chunks [{:chunk_id "c1" :content_length 1000}
                                         {:chunk_id "c2" :content_length 2000}])
                 (assoc :refunded-chunk-ids #{"c1"}))
          signal {:confidence 0.7
                  :degraded? false
                  :status :support-found
                  :chunk-ids ["c1" "c2"]
                  :supported-claims []}
          {:keys [refunded-ids refunded-chars]} (workspace/compute-budget-refund ws signal)]
      (is (= ["c2"] refunded-ids))
      (is (= 2000 refunded-chars)))))

(deftest record-read-evaluation-applies-refund
  (testing "record-read-evaluation! subtracts refunded chars from :read-content-length and tracks ids"
    (let [!ws (atom {:chunks {"c1" {:chunk_id "c1" :content_length 1000}
                              "c2" {:chunk_id "c2" :content_length 2000}
                              "c3" {:chunk_id "c3" :content_length 3000}}
                     :read-content-length 6000
                     :refunded-chunk-ids #{}
                     :read-evaluations []
                     :evidence-plan {:required-claims []}
                     :claim-coverage {}
                     :evidence-contradictions []
                     :non-supporting-chunk-ids #{}})
          signal {:confidence 0.8
                  :degraded? false
                  :status :support-found
                  :scope-assessment :aligned
                  :chunk-ids ["c1" "c2" "c3"]
                  :supported-claims [{:claim-id :topic-match :chunk-ids ["c1"]}]
                  :contradictions []
                  :remaining-gaps []}]
      (workspace/record-read-evaluation! !ws signal)
      (let [ws @!ws]
        (is (= 1000 (:read-content-length ws))
            "6000 initial − 5000 refunded (c2 + c3) = 1000")
        (is (= #{"c2" "c3"} (:refunded-chunk-ids ws)))))))

(deftest record-read-evaluation-does-not-double-refund
  (testing "Running record-read-evaluation! twice with same signal refunds only once"
    (let [!ws (atom {:chunks {"c1" {:chunk_id "c1" :content_length 1000}
                              "c2" {:chunk_id "c2" :content_length 2000}}
                     :read-content-length 3000
                     :refunded-chunk-ids #{}
                     :read-evaluations []
                     :evidence-plan {:required-claims []}
                     :claim-coverage {}
                     :evidence-contradictions []
                     :non-supporting-chunk-ids #{}})
          signal {:confidence 0.7
                  :degraded? false
                  :status :support-found
                  :scope-assessment :aligned
                  :chunk-ids ["c1" "c2"]
                  :supported-claims []
                  :contradictions []
                  :remaining-gaps []}]
      (workspace/record-read-evaluation! !ws signal)
      (let [after-first (:read-content-length @!ws)]
        (workspace/record-read-evaluation! !ws signal)
        (is (= after-first (:read-content-length @!ws))
            "Second evaluation must not refund chunks already refunded")
        (is (= #{"c1" "c2"} (:refunded-chunk-ids @!ws)))))))

;; -----------------------------------------------------------------------------
;; expand-short-doc-reads (f85c984): server-side SMALL-DOC expansion
;; -----------------------------------------------------------------------------

(defn- ws-with-summaries
  "Build a workspace whose :search-history contains one entry with the given
   chunk-summaries — the minimal shape expand-short-doc-reads needs."
  [summaries]
  {:search-history [{:chunk-summaries summaries}]})

(deftest expand-short-doc-reads-promotes-short-doc-to-whole-doc-range
  (testing "When a chunk_id belongs to a doc with :total-chunks ≤ 6, expand to whole doc"
    (let [ws (ws-with-summaries
              [{:chunk-id "short-1" :doc-num "DSHORT" :chunk-index 0 :total-chunks 4}
               {:chunk-id "short-2" :doc-num "DSHORT" :chunk-index 1 :total-chunks 4}])
          {:keys [chunk-id-reads doc-range-reads short-doc-promotions]}
          (workspace/expand-short-doc-reads ws ["short-1"])]
      (is (= 1 short-doc-promotions) "One short-doc promotion")
      (is (= [] chunk-id-reads) "Leftover chunk-ids is empty")
      (is (= 1 (count doc-range-reads)))
      (is (= "DSHORT" (-> doc-range-reads first :doc-num)))
      (is (= 0 (-> doc-range-reads first :from)))
      (is (= 3 (-> doc-range-reads first :to))
          "Range covers full 4-chunk doc (chunk indices 0..3)")
      (is (= ["short-1"] (-> doc-range-reads first :origin-chunk-ids))
          "Origin records which chunk-id triggered the expansion"))))

(deftest expand-short-doc-reads-leaves-long-doc-alone
  (testing "Chunk from a doc with :total-chunks > 6 stays as a chunk-id read"
    (let [ws (ws-with-summaries
              [{:chunk-id "long-7" :doc-num "DLONG" :chunk-index 7 :total-chunks 12}])
          {:keys [chunk-id-reads doc-range-reads short-doc-promotions]}
          (workspace/expand-short-doc-reads ws ["long-7"])]
      (is (= 0 short-doc-promotions) "No expansion")
      (is (= ["long-7"] chunk-id-reads) "Kept as a normal chunk-id read")
      (is (= [] doc-range-reads)))))

;; ---------------------------------------------------------------------------
;; Budget-limit resolution from skill-params (the Lever-2 plumbing lift)
;; ---------------------------------------------------------------------------

(deftest resolve-budget-limits-from-skill-params-defaults
  (testing "No parameters, no skill-params → the resolve-budget-limits defaults"
    (is (= (workspace/resolve-budget-limits {})
           (workspace/resolve-budget-limits-from-skill-params {} {}))
        "Empty inputs match the bare default resolution")))

(deftest resolve-budget-limits-from-skill-params-lifts-agent-keys
  (testing "Budget keys under [:builtin/agent] override the defaults"
    (let [limits (workspace/resolve-budget-limits-from-skill-params
                  {}
                  {:builtin/agent {:max-read-operations 10
                                   :max-read-content-length 24000}})]
      (is (= 10 (:max-read-operations limits)))
      (is (= 24000 (:max-read-content-length limits)))
      (is (= (:max-search-passes (workspace/resolve-budget-limits {}))
             (:max-search-passes limits))
          "Unset keys still fall through to defaults"))))

(deftest resolve-budget-limits-from-skill-params-skill-params-win-over-parameters
  (testing "A present skill-param overrides the same key in parameters"
    (let [limits (workspace/resolve-budget-limits-from-skill-params
                  {:max-read-operations 6}
                  {:builtin/agent {:max-read-operations 12}})]
      (is (= 12 (:max-read-operations limits))
          "skill-params take precedence over parameters")))
  (testing "An absent skill-param leaves the parameters value intact"
    (let [limits (workspace/resolve-budget-limits-from-skill-params
                  {:max-read-operations 9}
                  {:builtin/agent {}})]
      (is (= 9 (:max-read-operations limits))
          "parameters survive when skill-params omits the key"))))

;;; ---------------------------------------------------------------------------
;;; The publish seam (#460)
;;;
;;; `:chunks` is stored as a MAP keyed by chunk_id and published as a VECTOR.
;;; Handing the internal map to a consumer produced `structuredContent.chunks =
;;; [{} {}]`, because `mapv select-keys` over a map iterates `MapEntry` and
;;; `select-keys` on one returns `{}`. Every assertion here is on a shape or a
;;; populated field rather than a count: `[{} {}]` and two real chunks count
;;; the same, which is how this survived two tests that covered the area.
;;; ---------------------------------------------------------------------------

(def ^:private joined-chunks
  "Chunks as `digdir.rag.retrieval` returns them: the document join under a key
   named after the DOCS COLLECTION, never at the top level."
  [{:chunk_id "c1" :doc_num "7" :chunk_index 0 :content_length 900 :retrieval-prior 0.9
    :website_documents_ab897fbdedfa {:title "Årsrapport" :url "https://example.test/aar" :total_chunks 12}}
   {:chunk_id "c2" :doc_num "9" :chunk_index 1 :content_length 400 :retrieval-prior 0.5
    :KUDOS_preprod_v4_documents_ab897fbdedfa {:title "Tildelingsbrev" :total_chunks 4}}])

(deftest joined-document-is-found-under-any-collection-name
  (testing "the join is identified structurally, not by a hard-coded key"
    (is (= {:title "Årsrapport" :url "https://example.test/aar" :total_chunks 12}
           (workspace/joined-document (first joined-chunks))))
    (is (= {:title "Tildelingsbrev" :total_chunks 4}
           (workspace/joined-document (second joined-chunks)))
        "a different dataset means a different collection key, and it must still resolve"))
  (testing "a chunk with no join yields nil rather than an arbitrary nested map"
    (is (nil? (workspace/joined-document {:chunk_id "c1" :metadata {:some "map"}})))))

(deftest chunks-for-output-publishes-a-vector-of-populated-chunks
  (let [published (workspace/chunks-for-output (workspace-with-chunks joined-chunks))]
    (testing "the published shape is a vector, not the internal chunk map"
      (is (vector? published))
      (is (every? map? published)
          "MapEntry elements are what `select-keys` turns into {}"))
    (testing "every published chunk carries keys"
      (is (every? seq published)))
    (testing "identifiers survive"
      (is (= #{"c1" "c2"} (set (map :chunk_id published)))))
    (testing "the joined document is lifted to the top level, where consumers read it"
      (let [by-id (into {} (map (juxt :chunk_id identity)) published)]
        (is (= "Årsrapport" (get-in by-id ["c1" :title])))
        (is (= "https://example.test/aar" (get-in by-id ["c1" :url])))
        (is (= 12 (get-in by-id ["c1" :total_chunks])))
        (is (= "Tildelingsbrev" (get-in by-id ["c2" :title])))))
    (testing "the nested join is kept, because consumers still scan for it structurally"
      (is (some? (workspace/joined-document (first published)))))))

(deftest chunk-for-output-does-not-invent-a-url
  (testing "a document without a url stays without one, so the caller's fallback still fires"
    (let [published (workspace/chunk-for-output
                     {:chunk_id "c2" :doc_num "9"
                      :website_documents_ab897fbdedfa {:title "T" :total_chunks 4}})]
      (is (not (contains? published :url))
          "inventing an empty url would suppress the digdir://doc/<n> fallback")
      (is (= "T" (:title published)))))
  (testing "a blank url is treated as absent rather than lifted"
    (let [published (workspace/chunk-for-output
                     {:chunk_id "c3" :doc_num "1"
                      :website_documents_ab897fbdedfa {:title "T" :url ""}})]
      (is (not (contains? published :url)))))
  (testing "a chunk with no join at all passes through unchanged"
    (let [chunk {:chunk_id "c4" :doc_num "2"}]
      (is (= chunk (workspace/chunk-for-output chunk))))))
