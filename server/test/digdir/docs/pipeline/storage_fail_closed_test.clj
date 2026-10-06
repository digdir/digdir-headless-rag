(ns digdir.docs.pipeline.storage-fail-closed-test
  "A storage write FAILS when Typesense refuses a row.

   A Typesense bulk import answers HTTP 200 with one result per row and does
   not throw when a row is refused: `{:success false :error ...}` is an
   ordinary element of an ordinary return value. Storage reads that answer and
   throws, so no caller has to remember to. A single-document write already
   throws in the client; storage re-raises it in the same shape.

   These tests fake Typesense at the `typesense.client` boundary, and every
   client function they do not fake throws, so nothing here reaches a real
   Typesense."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.docs.loader :as loader]
            [digdir.docs.pipeline.orchestration :as orchestration]
            [digdir.docs.pipeline.storage :as storage]
            [digdir.docs.pipeline.telemetry :as telemetry]
            [digdir.rag.typesense :as ts-utils]
            [missionary.core :as m]
            [typesense.client :as ts]))

(def ^:private refusal
  "Typesense's error for a row whose document is not in the referenced collection (as returned by Typesense 30.2)."
  "Reference document having `doc_num:= `new`` not found in the collection `q5_documents_v1`.")

(def ^:private body-marker
  "Planted in every row's content: it must never appear in an error."
  "BODY-TEXT-MUST-NOT-LEAK")

(defn- fake-typesense
  "Var -> replacement for every public `typesense.client` function. `refuse?`
   decides per [collection row] whether the bulk import refuses that row.
   Every unfaked function throws. `calls` records each write, in order."
  [calls refuse?]
  (merge
   (into {} (for [[sym v] (ns-publics 'typesense.client)]
              [v (fn [& _] (throw (ex-info (str "unexpected Typesense call: typesense.client/" sym) {})))]))
   {#'ts/create-collection! (fn [_ schema] (swap! calls conj [:create (:name schema)]) schema)
    #'ts/upsert-document!   (fn [_ coll doc] (swap! calls conj [:upsert-one coll (:id doc)]) doc)
    #'ts/upsert-documents!  (fn [_ coll rows]
                              (let [rows (vec rows)]
                                (swap! calls conj [:upsert-many coll (mapv :id rows)])
                                (mapv (fn [row] (if (refuse? coll row)
                                                  {:success false :code 400 :error refusal}
                                                  {:success true}))
                                      rows)))
    #'ts/delete-documents!  (fn [_ coll _] (swap! calls conj [:delete coll]) {:num_deleted 0})
    #'ts-utils/make-ts-settings (fn [_] {:uri "http://stub:8108" :key "stub"})}))

(defn- with-typesense
  ([refuse? f] (with-typesense refuse? {} f))
  ([refuse? redefs f]
   (let [calls (atom [])]
     (with-redefs-fn (merge (fake-typesense calls refuse?) redefs) #(f calls)))))

(defn- thrown [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(defn- assert-refusal
  "The ruled shape of a refusal: type, counts, a capped sample of ids and
   Typesense's messages, and a message that names all of it - but no row body."
  [e {:keys [collection sent written refused ids]}]
  (is (some? e) "the write must THROW on a refused row")
  (when e
    (let [d (ex-data e)]
      (is (= :digdir.storage/rows-refused (:type d)) (pr-str d))
      (is (= {:collection collection :sent sent :written written :refused refused}
             (select-keys d [:collection :sent :written :refused])))
      (is (= ids (mapv :id (:refused-sample d))) "the refused rows are named by the ids that were sent")
      (is (every? #(= refusal (:error %)) (:refused-sample d)) "each carries Typesense's own reason")
      (is (str/includes? (ex-message e) collection) "the message names the collection")
      (is (str/includes? (ex-message e) (str refused " of " sent)) "the message carries the counts")
      (is (str/includes? (ex-message e) (first ids)) "the message names a refused id")
      (is (not (str/includes? (str (ex-message e) (pr-str d)) body-marker))
          "no row body in the message or the data"))))

;; =============================================================================
;; 1. One write
;; =============================================================================

(def ^:private chunks
  [{:chunk_id "c1" :content_markdown body-marker} {:chunk_id "c2" :content_markdown body-marker}])

(deftest a-refused-row-fails-the-bulk-write
  (with-typesense (fn [_ row] (= "c2" (:id row)))
    (fn [_]
      (assert-refusal (thrown #(storage/store-chunks! {:tenant "t"} "t_chunks_x" chunks))
                      {:collection "t_chunks_x" :sent 2 :written 1 :refused 1 :ids ["c2"]}))))

(deftest a-short-answer-counts-the-unanswered-rows-as-refused
  ;; Written means CONFIRMED. A row with no result in Typesense's answer was not
  ;; confirmed, whatever the HTTP status said.
  (with-typesense (constantly false)
    {#'ts/upsert-documents! (fn [_ _ _] [{:success true}])}
    (fn [_]
      (let [e (thrown #(storage/store-chunks! {:tenant "t"} "t_chunks_x"
                                              (conj chunks {:chunk_id "c3" :content_markdown body-marker})))]
        (is (some? e) "a short answer must fail the write")
        (when e
          (is (= {:sent 3 :written 1 :refused 2} (select-keys (ex-data e) [:sent :written :refused])))
          (is (= ["c2" "c3"] (mapv :id (:refused-sample (ex-data e)))))
          (is (every? #(= "no result for this row in Typesense's response" (:error %))
                      (:refused-sample (ex-data e)))))))))

(deftest an-accepted-write-returns-the-rows-written-and-an-empty-one-sends-nothing
  (with-typesense (constantly false)
    (fn [calls]
      (is (= 2 (storage/store-chunks! {:tenant "t"} "t_chunks_x" chunks)))
      (reset! calls [])
      (is (= 0 (storage/store-chunks! {:tenant "t"} "t_chunks_x" [])))
      (is (= 0 (storage/store-phrases! {:tenant "t"} "t_phrases_x" [] "d")))
      (is (empty? @calls) "an empty write sends nothing"))))

(deftest a-refused-single-row-is-re-raised-in-the-same-shape
  (let [client-error (ex-info "Field `title` must be a string." {:type :typesense.client/bad-request})]
    (with-typesense (constantly false)
      {#'ts/upsert-document! (fn [& _] (throw client-error))}
      (fn [_]
        (let [e (thrown #(storage/upsert-document! {:tenant "t"} "t_documents_x"
                                                   {:id "d1" :content body-marker}))]
          (is (some? e))
          (when e
            (is (= :digdir.storage/rows-refused (:type (ex-data e))) (pr-str (ex-data e)))
            (is (= {:collection "t_documents_x" :sent 1 :written 0 :refused 1}
                   (select-keys (ex-data e) [:collection :sent :written :refused])))
            (is (= [{:id "d1" :error "Field `title` must be a string."}] (:refused-sample (ex-data e))))
            (is (identical? client-error (ex-cause e)) "the client's own error is the cause")))))))

(deftest the-error-caps-what-it-carries
  (let [n 40
        long-error (apply str (repeat 400 "x"))]
    (with-typesense (constantly false)
      {#'ts/upsert-documents! (fn [_ _ rows] (mapv (fn [_] {:success false :error long-error}) rows))}
      (fn [_]
        (let [e (thrown #(storage/store-chunks! {:tenant "t"} "t_chunks_x"
                                                (mapv (fn [i] {:chunk_id (str "c" i)}) (range n))))
              d (ex-data e)]
          (is (= n (:refused d)) "the COUNT is exact")
          (is (= 20 (count (:refused-sample d))) "the named sample is capped at 20")
          (is (every? #(<= (count (:error %)) 300) (:refused-sample d)) "each error is capped at 300 chars")
          (is (= 5 (count (re-seq #"\bc\d+\b" (str (second (re-find #"\(ids: ([^)]*)\)" (str (ex-message e))))))))
              "the message names at most 5 ids"))))))

;; =============================================================================
;; 2. One document: the refusal stops the write BEFORE that collection's orphan delete
;; =============================================================================
;;
;; The orphan delete keeps only the ids just sent. A refused row's id is new
;; (content-hashed), so deleting after a refusal would remove the old revision of
;; exactly the row whose replacement was refused.

(def ^:private a-document
  {:id "doc-1" :doc_num "dn-1" :title "One"
   :chunks [{:chunk_id "c1" :doc_num "dn-1" :chunk_index 0 :content_markdown body-marker
             :search-phrases ["p1" "p2"]}
            {:chunk_id "c2" :doc_num "dn-1" :chunk_index 1 :content_markdown body-marker
             :search-phrases ["p3"]}]})

(defn- store-complete! [_]
  (storage/store-complete-document! {:tenant "t"} a-document
                                    (fn [_ d] (select-keys d [:id :doc_num :title :total_chunks]))
                                    (fn [cs] (mapv #(dissoc % :search-phrases) cs))))

(defn- store-kudos! [_]
  ((loader/store-doc {:store/type :typesense} {:tenant "t" :store/coll-prefix "t_"}) a-document))

(defn- deletes-of
  "Every delete issued against `coll`, before or after its write."
  [calls coll]
  (filterv #(= [:delete coll] %) @calls))

(deftest a-refused-row-stops-the-document-before-that-collections-orphan-delete
  (doseq [[label store! colls-of] [["store-complete-document!" store-complete!
                                    (fn [] ["t_documents_x" "t_chunks_x" "t_phrases_x"])]
                                   ["the KUDOS store-doc" store-kudos!
                                    (fn [] (loader/coll-ids {:tenant "t" :store/coll-prefix "t_"}))]]
          [what pick] [["a chunk" second] ["a phrase" #(nth % 2)]]]
    (testing (str label ", " what " refused")
      (let [[docs-coll :as colls] (colls-of)
            target (pick colls)]
        (with-typesense (fn [coll _] (= coll target))
          {#'storage/coll-ids (fn [_] colls)}
          (fn [calls]
            (let [e (thrown #(store! nil))]
              (is (some? e) (str label " must throw when " what " is refused"))
              (is (= target (:collection (ex-data e))))
              (is (some #(= [:upsert-one docs-coll "doc-1"] %) @calls) "PREMISE: the document row was written")
              (is (= [] (deletes-of calls target))
                  "a refused write must leave that collection's old revision: no orphan delete on it, before or after"))))))))

(deftest a-document-that-writes-cleanly-still-deletes-its-orphans
  ;; The control for the test above: the ordering rule must not stop cleanup.
  (with-typesense (constantly false)
    {#'storage/coll-ids (fn [_] ["t_documents_x" "t_chunks_x" "t_phrases_x"])}
    (fn [calls]
      (store-complete! nil)
      (let [order (mapv (juxt first second) @calls)
            at (fn [step] (.indexOf ^java.util.List order step))]
        (is (= ["t_chunks_x" "t_phrases_x"] (mapv second (filter #(= :delete (first %)) @calls))))
        (doseq [coll ["t_chunks_x" "t_phrases_x"]]
          (is (< -1 (at [:upsert-many coll]) (at [:delete coll]))
              (str "the orphan delete on " coll " comes AFTER its write")))))))

;; =============================================================================
;; 3. Deletes by filter
;; =============================================================================

(deftest a-delete-by-filter-returns-what-it-deleted-and-passes-a-client-error-through
  (let [delete-by-filter! storage/delete-by-filter!]
    (with-typesense (constantly false)
      {#'ts/delete-documents! (fn [_ _coll opts] (is (= {:filter_by "doc_num:=d1"} opts)) {:num_deleted 4})}
      (fn [_]
        (is (= 4 (delete-by-filter! {:uri "u"} "t_chunks_x" "doc_num:=d1")))))
    (with-typesense (constantly false)
      {#'ts/delete-documents! (fn [& _] (throw (ex-info "boom" {:status 500})))}
      (fn [_]
        (is (= "boom" (some-> (thrown #(delete-by-filter! {:uri "u"} "c" "f")) ex-message))
            "a client error passes through")))))

;; =============================================================================
;; 4. A store that throws does not leave the store-threads gauge counting it
;; =============================================================================

(deftest a-throwing-store-leaves-the-store-threads-gauge-where-it-was
  (doseq [[label gauge run-it!] [["orchestration" telemetry/!store-threads
                         #(m/? (m/reduce (constantly nil)
                                         (orchestration/mk-store-documents-f
                                          {:parallelism/store 1}
                                          (fn [_ _] (m/sp (throw (ex-info "refused" {}))))
                                          (m/seed [{:id "a"}]))))]
                        ["the KUDOS loader" loader/!store-threads
                         #(with-redefs [loader/mk-store-document-t (fn [_ _] (m/sp (throw (ex-info "refused" {}))))]
                            (m/? (m/reduce (constantly nil)
                                           (loader/mk-store-documents-f {:parallelism/store 1} (m/seed [{:id "a"}])))))]]]
    (testing label
      (let [before @gauge]
        (is (thrown? clojure.lang.ExceptionInfo (run-it!)))
        (is (= before @gauge) "a throwing store must decrement what it incremented")))))

;; =============================================================================
;; 5. Only Typesense saying THIS ROW's data is unacceptable counts as refused
;; =============================================================================

(def ^:private client-error-types
  "Each `:type` the client raises, and whether a single-row write counts it as a refusal."
  {:typesense.client/bad-request true
   :typesense.client/unprocessable-entity true
   :typesense.client/unauthorized false
   :typesense.client/not-found false
   :typesense.client/conflict false
   :typesense.client/service-unavailable false
   :typesense.client/unspecified-api-error false})

(deftest only-a-data-refusal-of-a-single-row-counts-as-refused
  (is (= #{:typesense.client/bad-request :typesense.client/unprocessable-entity}
         (some-> (ns-resolve 'digdir.docs.pipeline.storage 'refusal-types) deref))
      "ONE definition, with exactly these members")
  (doseq [[t refused?] client-error-types]
    (testing (name t)
      (let [client-error (ex-info (str "client says " (name t)) {:type t :message "x"})]
        (with-typesense (constantly false)
          {#'ts/upsert-document! (fn [& _] (throw client-error))}
          (fn [_]
            (let [e (thrown #(storage/upsert-document! {:tenant "t"} "t_documents_x" {:id "d1"}))]
              (if refused?
                (is (= :digdir.storage/rows-refused (:type (ex-data e))) "a data refusal: counted against the budget")
                (is (identical? client-error e)
                    "anything else (an outage, a missing collection, a bad key) propagates UNCHANGED and fails the run"))))))))
  (testing "a non-client exception propagates unchanged"
    (let [io (java.io.IOException. "connection reset")]
      (with-typesense (constantly false)
        {#'ts/upsert-document! (fn [& _] (throw io))}
        (fn [_]
          (is (identical? io (try (storage/upsert-document! {:tenant "t"} "c" {:id "d"}) nil
                                  (catch Exception e e)))))))))

(deftest an-id-less-refusal-says-how-many-rows-not-an-empty-id-list
  (with-typesense (constantly true)
    (fn [_]
      (let [e (thrown #(storage/upsert-rows! {:uri "u"} "t_questions_x" [{:question "a"} {:question "b"}]))]
        (is (str/includes? (str (ex-message e)) "(2 rows without ids)") (str (ex-message e)))
        (is (not (str/includes? (str (ex-message e)) "(ids:")) "never an empty id list")))))
