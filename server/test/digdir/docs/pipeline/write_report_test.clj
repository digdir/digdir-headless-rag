(ns digdir.docs.pipeline.write-report-test
  "The write step: A WRITE RETURNS WHAT IT ACTUALLY WROTE.

   A Typesense bulk import answers HTTP 200 with one result per row and does
   not throw when a row is refused: `{:success false :error ...}` is an
   ordinary element of an ordinary return value. Every ingest write site
   dropped that vector, so a refused row left no error, no count and no signal,
   and the run said `:completed` (the phrases-reference tripwire, measured on Typesense 30.2).

   These tests fake Typesense at the `typesense.client` boundary, and EVERY
   client function they do not fake is trapped to throw, so nothing here can
   reach a real Typesense. The fake answers per row the way Typesense does and
   refuses every row written to a PHRASES collection, which is the phrases-reference tripwire's shape: a
   phrases collection that still references a superseded documents collection
   rejects the phrases of every document it has never seen.

   What each test asserts is the RETURN VALUE, because that is the property:
   after this, a caller can tell \"I wrote these\" from \"I wrote nothing\" from
   \"some rows were refused\" from what the write gave back, not from a log.

   Out of scope, deliberately: how a run's terminal status is decided (the silent Azure no-key run issue's
   property A). The loaders now RETURN what they wrote; the executor still
   ignores it. That is the next step, and it reads what these tests pin."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.docs.episerver :as episerver]
            [digdir.docs.folder :as folder]
            [digdir.docs.loader :as loader]
            [digdir.docs.pipeline.orchestration :as orchestration]
            [digdir.docs.pipeline.storage :as storage]
            [digdir.docs.schema-drift :as schema-drift]
            [digdir.docs.website :as website]
            [digdir.llm.kudos :as kudos]
            [digdir.rag.typesense :as ts-utils]
            [missionary.core :as m]
            [typesense.client :as ts]))

;; =============================================================================
;; A Typesense that answers per row
;; =============================================================================

(def ^:private refusal
  "The error Typesense 30.2 returned for a phrase whose document is not in the
   documents collection its phrases collection references (measured)."
  "Reference document having `doc_num:= `new`` not found in the collection `q5_documents_v1`.")

(defn- refused? [coll] (str/includes? coll "phrases"))

(defn- fake-typesense
  "Var -> replacement for every public `typesense.client` function. The four a
   store uses are faked; every other one THROWS, so a test cannot pass by
   reaching something real. `calls` records what was written where."
  [calls]
  (merge
   (into {} (for [[sym v] (ns-publics 'typesense.client)]
              [v (fn [& _] (throw (ex-info (str "unexpected Typesense call: typesense.client/" sym) {})))]))
   {#'ts/create-collection! (fn [_ schema] (swap! calls conj [:create (:name schema)]) schema)
    #'ts/upsert-document!   (fn [_ coll doc] (swap! calls conj [:upsert-one coll (:id doc)]) doc)
    #'ts/upsert-documents!  (fn [_ coll rows]
                              (let [rows (vec rows)]
                                (swap! calls conj [:upsert-many coll (count rows)])
                                (mapv (fn [_] (if (refused? coll)
                                                {:success false :code 400 :error refusal}
                                                {:success true}))
                                      rows)))
    #'ts/delete-documents!  (fn [_ coll _] (swap! calls conj [:delete coll]) {:num_deleted 0})
    #'ts-utils/make-ts-settings (fn [_] {:uri "http://stub:8108" :key "stub"})}))

(defn- with-typesense [redefs f]
  (let [calls (atom [])]
    (with-redefs-fn (merge (fake-typesense calls) redefs) #(f calls))))

(defn- run-task [t] (m/? t))

;; =============================================================================
;; 1. One bulk write
;; =============================================================================

(deftest a-bulk-write-returns-the-rows-typesense-refused
  (doseq [[label store! coll rows]
          [["store-chunks!"
            (fn [coll rows] (storage/store-chunks! {:tenant "t"} coll rows))
            "t_chunks_x"
            [{:chunk_id "c1"} {:chunk_id "c2"}]]
           ["store-phrases!"
            (fn [coll rows] (storage/store-phrases! {:tenant "t"} coll rows "doc-1"))
            "t_phrases_x"
            [{:chunk_id "c1" :search_phrase "alpha"} {:chunk_id "c1" :search_phrase "beta"}]]]]
    (testing label
      (with-typesense
        {#'ts/upsert-documents! (fn [_ _ rows]
                                  ;; the first row lands, the second is refused
                                  (mapv (fn [i] (if (zero? i) {:success true} {:success false :error refusal}))
                                        (range (count rows))))}
        (fn [_]
          (let [r (store! coll rows)]
            (is (= {:collection coll :sent 2 :written 1} (select-keys r [:collection :sent :written]))
                (str label " must say what it wrote: " (pr-str r)))
            (is (= 1 (count (:rejected r))) (str label " must name the refused row: " (pr-str r)))
            (is (= refusal (:error (first (:rejected r))))
                "the refusal carries Typesense's own reason")
            (is (some? (:id (first (:rejected r))))
                "the refused row is named by the id that was SENT, not only counted")))))))

(deftest a-response-that-confirms-fewer-rows-than-were-sent-is-not-counted-as-written
  ;; Written means CONFIRMED. A row with no result in the response was not
  ;; confirmed, so it is not written, whatever the HTTP status said.
  (with-typesense
    {#'ts/upsert-documents! (fn [_ _ _] [{:success true}])}
    (fn [_]
      (let [r (storage/store-chunks! {:tenant "t"} "t_chunks_x"
                                     [{:chunk_id "c1"} {:chunk_id "c2"} {:chunk_id "c3"}])]
        (is (= {:sent 3 :written 1} (select-keys r [:sent :written])) (pr-str r))
        (is (= ["c2" "c3"] (mapv :id (:rejected r)))
            "the unconfirmed rows are named, in the order they were sent")))))

(deftest an-empty-write-says-it-wrote-nothing
  ;; The zero case must be a REPORT of zero, not nil: nil is what every write
  ;; returned before, and it cannot be told apart from \"did not report\".
  (with-typesense {}
    (fn [calls]
      (doseq [[label r] [["store-chunks!" (storage/store-chunks! {:tenant "t"} "t_chunks_x" [])]
                         ["store-phrases!" (storage/store-phrases! {:tenant "t"} "t_phrases_x" [] "d")]]]
        (testing label
          (is (= {:sent 0 :written 0 :rejected []} (select-keys r [:sent :written :rejected])) (pr-str r))))
      (is (empty? @calls) "and, as before, an empty write sends nothing"))))

;; =============================================================================
;; 2. One document: every collection it touched
;; =============================================================================

(def ^:private a-document
  {:id "doc-1" :doc_num "dn-1" :title "One" :path "/corpus/one.md" :url "https://x.test/one"
   :chunks [{:chunk_id "c1" :doc_num "dn-1" :chunk_index 0 :content_markdown "x"
             :search-phrases ["p1" "p2"]}
            {:chunk_id "c2" :doc_num "dn-1" :chunk_index 1 :content_markdown "y"
             :search-phrases ["p3"]}]})

(defn- writes-by-collection [doc-report]
  (into {} (for [w (:writes doc-report)] [(:collection w) (select-keys w [:sent :written])])))

(deftest store-complete-document-returns-what-each-collection-wrote
  (with-typesense
    {#'storage/coll-ids (fn [_] ["t_documents_x" "t_chunks_x" "t_phrases_x"])}
    (fn [_]
      (let [r (storage/store-complete-document! {:tenant "t"} a-document
                                                (fn [_ d] (select-keys d [:id :doc_num :title :total_chunks]))
                                                (fn [chunks] (mapv #(dissoc % :search-phrases) chunks)))]
        (is (= "doc-1" (:document-id r)) (pr-str r))
        (is (= {"t_documents_x" {:sent 1 :written 1}
                "t_chunks_x"    {:sent 2 :written 2}
                "t_phrases_x"   {:sent 3 :written 0}}
               (writes-by-collection r))
            "every phrase refused, and the document's own report says so")))))

(deftest the-kudos-typesense-store-returns-what-it-wrote
  (with-typesense {}
    (fn [_]
      (let [kview {:tenant "t" :store/coll-prefix "t_"}
            [docs-coll chunks-coll phrases-coll] (loader/coll-ids kview)
            r ((loader/store-doc {:store/type :typesense} kview) a-document)]
        (is (= "doc-1" (:document-id r)) (pr-str r))
        (is (= {docs-coll    {:sent 1 :written 1}
                chunks-coll  {:sent 2 :written 2}
                phrases-coll {:sent 3 :written 0}}
               (writes-by-collection r))
            "the kudos loader's own store, which does not go through storage.clj")))))

;; =============================================================================
;; 3. A whole materialization: the loader's task returns what the run wrote
;; =============================================================================
;;
;; Fetching, reading and phrasing are replaced (they reach files, sitemaps, the
;; Kudos API and an LLM). The STORE step and the FOLD over it are the real ones,
;; because those are what changed.

(defn- prepared [id]
  (-> a-document
      (assoc :id id :doc_num (str "dn-" id) :path (str "/corpus/" id ".md") :url (str "https://x.test/" id))
      ;; Each chunk carries its document's location: website's and folder's
      ;; chunk preparation relativise a chunk's own `:url` / `:path`.
      (update :chunks (fn [cs] (mapv #(assoc % :doc_num (str "dn-" id) :chunk_id (str id "-" (:chunk_id %))
                                             :url (str "https://x.test/" id) :path (str "/corpus/" id ".md"))
                                     cs)))))

(def ^:private common
  {:tenant "t" :store/coll-prefix "t_" :parallelism/documents 1 :parallelism/store 1
   :fault-tolerance/max-document-failures 10})

(def ^:private loaders
  "Each loader on the executor's path, with what it needs to run two documents."
  [{:label "folder"
    :config (assoc common :folder/path "/corpus" :files/limit 10)
    :coll-ids storage/coll-ids
    :redefs {#'folder/find-markdown-files (fn [_] [{:path "/corpus/a.md"} {:path "/corpus/b.md"}])
             #'folder/mk-prepare-document-t (fn [_ e] (m/sp (prepared (str/replace (:path e) #".*/|\.md$" ""))))}
    :task folder/mk-materialize-t}
   {:label "website"
    :config (assoc common :base-url "https://x.test" :sitemap/url "/sitemap.xml" :urls/limit 10)
    :coll-ids storage/coll-ids
    :redefs {#'website/parse-sitemap (fn [& _] [{:loc "https://x.test/a.md"} {:loc "https://x.test/b.md"}])
             #'website/mk-prepare-document-t (fn [_ e] (m/sp (prepared (str/replace (:loc e) #".*/|\.md$" ""))))}
    :task website/mk-materialize-t}
   {:label "episerver"
    :config (assoc common :xml/path "/export.xml" :language "no")
    :coll-ids storage/coll-ids
    :redefs {#'episerver/extract-pages-streaming (fn [_] [{:page "a"} {:page "b"}])
             #'episerver/filter-published-pages identity
             #'episerver/filter-deleted-pages identity
             #'episerver/filter-by-language (fn [pages _] pages)
             #'episerver/mk-prepare-document-t (fn [_ p] (m/sp (prepared (:page p))))}
    :task episerver/mk-materialize-t}
   {:label "kudos"
    :config (assoc common :stores #{{:store/type :typesense}} :documents/first-import-ids ["a" "b"])
    :coll-ids loader/coll-ids
    :redefs {#'kudos/documents-by-ids (fn [_ ids] (m/seed (map (fn [id] {:id id}) ids)))
             #'kudos/documents (fn [& _] (m/seed []))
             #'loader/mk-prepare-document-t (fn [_ d] (m/sp (prepared (:id d))))
             #'schema-drift/report! (fn [& _] nil)}
    :task loader/mk-materialize-t}])

(deftest a-materialization-returns-what-the-run-wrote
  (doseq [{:keys [label config redefs task] coll-ids-of :coll-ids} loaders]
    (testing label
      (with-typesense redefs
        (fn [calls]
          (let [[docs-coll chunks-coll phrases-coll] (coll-ids-of config)
                r (run-task (task config))]
            (is (seq (filter #(= :upsert-many (first %)) @calls))
                (str label ": PREMISE: the store step ran against the fake"))
            (is (= 2 (:documents r)) (str label ": both documents reached the store: " (pr-str r)))
            (is (= {docs-coll    {:sent 2 :written 2}
                    chunks-coll  {:sent 4 :written 4}
                    phrases-coll {:sent 6 :written 0}}
                   (:collections r))
                (str label ": the run's report is the sum of what each write confirmed"))
            (is (seq (:rejected-sample r)) (str label ": the refused rows are named, not only counted"))
            (is (every? #(= phrases-coll (:collection %)) (:rejected-sample r))
                (str label ": and each names the collection that refused it"))))))))

(deftest the-other-kudos-store-flows-return-what-they-wrote
  ;; `mk-import-single-document-t` (the `-main-single` CLI) and
  ;; `retry-failed-documents!` (the legacy document-loading UI) fold the same
  ;; store flow as the kudos materialization. Neither is on the executor's path;
  ;; both folds changed, so both are pinned.
  (let [kview (assoc common :stores #{{:store/type :typesense}})
        [_ _ phrases-coll] (loader/coll-ids kview)
        failed-before @loader/!failed-documents]
    (with-typesense {#'kudos/documents-by-ids (fn [_ ids] (m/seed (map (fn [id] {:id id}) ids)))
                     #'loader/mk-prepare-document-t (fn [_ d] (m/sp (prepared (:id d))))
                     #'schema-drift/report! (fn [& _] nil)}
      (fn [_]
        (testing "single import"
          (let [r (run-task (loader/mk-import-single-document-t kview "a"))]
            (is (= 1 (:documents r)) (pr-str r))
            (is (= {:sent 3 :written 0} (get-in r [:collections phrases-coll])))))
        (testing "retry of failed documents"
          (try
            (reset! loader/!failed-documents ["a" "b"])
            (let [r (loader/retry-failed-documents! kview)]
              (is (= 2 (:documents r)) (pr-str r))
              (is (= {:sent 6 :written 0} (get-in r [:collections phrases-coll]))))
            (finally (reset! loader/!failed-documents failed-before))))))))

(deftest the-shared-orchestration-folds-what-its-store-step-returns
  ;; `orchestration/mk-materialize-t` is reached only through
  ;; `protocol/mk-materialize-t`, which nothing calls today. It folds the same
  ;; way as the loaders above, so the next loader built on it inherits a
  ;; report rather than `rfs/last`.
  ;; The store step's reports are written out as DATA: the shape is the
  ;; contract the fold reads, so it is pinned literally rather than rebuilt with
  ;; the constructors under test.
  (let [store (fn [_ doc]
                (m/sp {:document-id (:id doc)
                       :writes [{:collection "t_chunks_x" :sent 1 :written 1 :rejected []}
                                {:collection "t_phrases_x" :sent 1 :written 0
                                 :rejected [{:id (str (:id doc) "-p") :error refusal}]}]}))
        r (run-task (orchestration/mk-materialize-t
                     (assoc common :urls/limit 10)
                     (m/sp [{:loc "a"} {:loc "b"}])
                     orchestration/mk-filter-url-entries-f
                     (fn [_ e] (m/sp {:id (:loc e)}))
                     store
                     :test))]
    (is (= 2 (:documents r)) (pr-str r))
    (is (= {"t_chunks_x" {:sent 2 :written 2} "t_phrases_x" {:sent 2 :written 0}} (:collections r)))
    (is (= #{"a-p" "b-p"} (set (map :id (:rejected-sample r)))))))

(deftest a-kudos-run-with-no-store-says-it-wrote-nothing
  ;; through the executor the kudos loader is handed no `:stores`, so the
  ;; store step joins over NOTHING and returns. The run's report must show the
  ;; documents reaching the store and no write at all, rather than looking like
  ;; any other finished run.
  (with-typesense {#'kudos/documents-by-ids (fn [_ ids] (m/seed (map (fn [id] {:id id}) ids)))
                   #'kudos/documents (fn [& _] (m/seed []))
                   #'loader/mk-prepare-document-t (fn [_ d] (m/sp (prepared (:id d))))}
    (fn [calls]
      (let [r (run-task (loader/mk-materialize-t (assoc common :documents/first-import-ids ["a" "b"])))]
        (is (= 2 (:documents r)) (pr-str r))
        (is (= {} (:collections r)) "no collection was written")
        (is (empty? @calls) "PREMISE: nothing reached Typesense")))))
