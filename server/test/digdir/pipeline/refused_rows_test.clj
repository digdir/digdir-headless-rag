(ns digdir.pipeline.refused-rows-test
  "A document whose rows Typesense refuses is a document FAILURE: tolerated up
   to the run's `:fault-tolerance/max-document-failures` budget, which it shares
   with prepare failures, and never silent.

   Each executor arm runs the real `execute-pipeline!`, the real loader dispatch
   and the real loader, against a Typesense faked at the `typesense.client`
   boundary that keeps rows per collection (every unfaked client function
   throws). Only listing the source and preparing a document are replaced (they
   reach files, sitemaps, an export, the Kudos API and an LLM).

   Each document starts with an OLD revision already stored, so the tests can
   see whether its orphan delete ran."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.data.db :as db]
            [digdir.docs.episerver :as episerver]
            [digdir.docs.folder :as folder]
            [digdir.docs.loader :as loader]
            [digdir.docs.pipeline.orchestration :as orchestration]
            [digdir.docs.pipeline.storage :as storage]
            [digdir.docs.schema-drift :as schema-drift]
            [digdir.docs.website :as website]
            [digdir.llm.kudos :as kudos]
            [digdir.pipeline.core :as pipeline]
            [digdir.pipeline.executor :as executor]
            [digdir.pipeline.integration-test :as it]
            [digdir.rag.typesense :as ts-utils]
            [missionary.core :as m]
            [taoensso.telemere :as t]
            [typesense.client :as ts]))

;; =============================================================================
;; A Typesense that keeps rows
;; =============================================================================

(def ^:private refusal "Error with field `doc_num`: Value cannot be empty.")

(defn- matches-filter?
  "The orphan-delete filter storage builds: `doc_num:=X && id:!=[a,b]`."
  [filter-by row]
  (let [[_ doc-num ids] (re-find #"^doc_num:=(\S+) && id:!=\[([^\]]*)\]$" filter-by)]
    (assert doc-num (str "unexpected filter " filter-by))
    (and (= doc-num (:doc_num row))
         (not (contains? (set (str/split ids #",")) (:id row))))))

(defn- fake-typesense
  "`store`: an atom {collection {id row}}. `refuse?` [coll row] refuses a row of
   a bulk import; `explode?` [coll rows] makes the whole bulk call throw, as a
   500 does; `doc-error` [coll doc] answers a single-document write with the
   client error `[type message]` it returns, as the client raises it per status."
  [store {:keys [refuse? explode? doc-error]
          :or {refuse? (constantly false) explode? (constantly false) doc-error (constantly nil)}}]
  (merge
   (into {} (for [[sym v] (ns-publics 'typesense.client)]
              [v (fn [& _] (throw (ex-info (str "unexpected Typesense call: typesense.client/" sym) {})))]))
   {#'ts/create-collection! (fn [_ schema] schema)
    #'ts/retrieve-collection (fn [_ coll] {:name coll :fields []})
    #'ts/upsert-document! (fn [_ coll doc]
                            (when-let [[t message] (doc-error coll doc)]
                              (throw (ex-info message {:type t :message message})))
                            (swap! store assoc-in [coll (:id doc)] doc)
                            doc)
    #'ts/upsert-documents! (fn [_ coll rows]
                             (when (explode? coll rows)
                               (throw (ex-info "Internal Server Error" {:type :typesense.client/unspecified-api-error})))
                             (mapv (fn [row]
                                     (if (refuse? coll row)
                                       {:success false :code 400 :error refusal}
                                       (do (swap! store assoc-in [coll (:id row)] row) {:success true})))
                                   rows))
    #'ts/delete-documents! (fn [_ coll {:keys [filter_by]}]
                             (let [doomed (filter #(matches-filter? filter_by (val %)) (get @store coll))]
                               (swap! store update coll #(apply dissoc % (map key doomed)))
                               {:num_deleted (count doomed)}))
    #'ts-utils/make-ts-settings (fn [_] {:uri "http://stub:8108" :key "stub"})}))

(defn- seed-old-revisions!
  "Each document's previous revision: one chunk and one phrase, under ids the
   current revision does not use."
  [store [_ chunks-coll phrases-coll] ids]
  (doseq [id ids]
    (swap! store assoc-in [chunks-coll (str id "-old")] {:id (str id "-old") :doc_num (str "dn-" id)})
    (swap! store assoc-in [phrases-coll (str id "-oldp")] {:id (str id "-oldp") :doc_num (str "dn-" id)})))

(defn- has? [store coll id] (contains? (get @store coll) id))

;; =============================================================================
;; Documents and sources
;; =============================================================================

(def ^:private doc-ids ["a" "b" "c"])

(defn- prepared [id]
  {:id id :doc_num (str "dn-" id) :title id :path (str "/corpus/" id ".md") :url (str "https://x.test/" id)
   :chunks (vec (for [c ["c1" "c2"]]
                  {:chunk_id (str id "-" c) :doc_num (str "dn-" id) :chunk_index 0 :content_markdown "x"
                   :url (str "https://x.test/" id) :path (str "/corpus/" id ".md")
                   :search-phrases [(str id " " c)]}))})

(defn- prepare-or-fail [fail-ids id]
  (m/sp (if (contains? fail-ids id) (throw (ex-info "could not prepare" {:id id})) (prepared id))))

(defn- sources
  "Each executor source, with the loader keys it needs beyond the folder
   pipeline's, and stubs for its outside-the-process steps. `fail-ids` fail to
   prepare; `doc-ids` are the source's entries."
  ([fail-ids] (sources fail-ids ["a" "b" "c"]))
  ([fail-ids doc-ids]
  [{:source-type :folder
    :loader-keys {:files/limit 10}
    :redefs {#'folder/find-markdown-files (fn [_] (mapv #(hash-map :path (str "/corpus/" % ".md")) doc-ids))
             #'folder/mk-prepare-document-t (fn [_ e] (prepare-or-fail fail-ids (str/replace (:path e) #".*/|\.md$" "")))}}
   {:source-type :website
    :loader-keys {:base-url "https://x.test" :sitemap/url "/sitemap.xml" :urls/limit 10}
    :redefs {#'website/parse-sitemap (fn [& _] (mapv #(hash-map :loc (str "https://x.test/" % ".md")) doc-ids))
             #'website/mk-prepare-document-t (fn [_ e] (prepare-or-fail fail-ids (str/replace (:loc e) #".*/|\.md$" "")))}}
   {:source-type :episerver
    :loader-keys {:xml/path "/export.xml" :language "no"}
    :redefs {#'episerver/extract-pages-streaming (fn [_] (mapv #(hash-map :page %) doc-ids))
             #'episerver/filter-published-pages identity
             #'episerver/filter-deleted-pages identity
             #'episerver/filter-by-language (fn [pages _] pages)
             #'episerver/mk-prepare-document-t (fn [_ p] (prepare-or-fail fail-ids (:page p)))}}]))

(def ^:private folder (first (sources #{})))

;; =============================================================================
;; Signals, captured synchronously, and the executor's own count of them
;; =============================================================================

(defn- with-signals
  "Run `f`, returning [its value, every signal emitted meanwhile]."
  [f]
  (let [seen (atom [])
        hid ::capture]
    (t/add-handler! hid (fn ([s] (swap! seen conj s)) ([])) {:async nil})
    (try [(f) @seen]
         (finally (t/remove-handler! hid)))))

(defn- telemetry-failures
  "What the executor's progress reducer counts as failures from `signals`: the
   number the panel shows while a run is live."
  [signals]
  (:failures (reduce #'executor/update-progress-from-signal @#'executor/empty-progress
                     (filter (comp keyword? :id) signals))))

;; =============================================================================
;; One run through the executor
;; =============================================================================

(defn- run-once! [{:keys [source-type loader-keys redefs]} ts-opts extra-loader-keys & {:keys [async?]}]
  (let [conn (db/get-conn)
        real-dispatch executor/dispatch-to-loader
        store (atom {})
        seen (atom nil)]
    (it/create-test-dataset! conn "public-docs" "Public Docs")
    (pipeline/create-pipeline! conn {:tenant "digdir" :tenant-config-key "default"
                                     :dataset-id "public-docs" :pipeline-name "altinn-docs"
                                     :properties it/folder-materialization-properties
                                     :master-key it/test-master-key})
    (let [[_ signals]
          (with-signals
            #(with-redefs-fn
               (merge (fake-typesense store ts-opts)
                      redefs
                      {#'executor/dispatch-to-loader
                       (fn [dataset-config loader-config]
                         (let [lc (merge loader-config loader-keys
                                         {:parallelism/documents 1 :parallelism/store 1}
                                         extra-loader-keys)
                               colls (storage/coll-ids lc)]
                           (reset! seen {:colls colls :loader-colls (storage/coll-ids loader-config)})
                           (seed-old-revisions! store colls doc-ids)
                           (real-dispatch (assoc dataset-config :source-type source-type) lc)))})
               (fn []
                 (if async?
                   ;; the console's path: the progress handler, the flusher, and the final flush
                   (let [id (executor/execute-pipeline-async! conn "digdir" "default" "altinn-docs"
                                                              it/test-master-key "test-user")]
                     (some-> (get @executor/!executions-futures id) deref))
                   (try (m/? (executor/execute-pipeline! conn "digdir" "default" "altinn-docs"
                                                        it/test-master-key "test-user"))
                        (catch Exception _ nil))))))]
      (assoc @seen
             :store store
             :signals signals
             :execution (first (executor/list-executions @conn "digdir:default:altinn-docs"))
             :names (mapv (pipeline/get-dataset @conn "digdir" "default" "altinn-docs" it/test-master-key)
                          [:docs-collection :chunks-collection :phrases-collection])))))

(defn- run-source!
  "`run-once!` on a fresh store (each run creates the same dataset)."
  ([source ts-opts] (run-source! source ts-opts {}))
  ([source ts-opts extra-loader-keys & opts]
   (let [result (atom nil)]
     (it/with-test-db #(reset! result (apply run-once! source ts-opts extra-loader-keys opts)))
     @result)))

(defn- refuse-chunk-of
  "Refuse ONE chunk row (`<id>-c2`) of each document in `ids`."
  [ids]
  (let [refused (set (map #(str % "-c2") ids))]
    (fn [coll row] (and (str/includes? coll "_chunks_") (contains? refused (:id row))))))

(defn- refused-events [signals] (filter #(= :pipeline/document-refused (:id %)) signals))

;; =============================================================================
;; One refused document is tolerated, counted once, named, and keeps its old revision
;; =============================================================================

(deftest a-refused-document-is-tolerated-counted-once-and-named
  (doseq [{:keys [source-type] :as source} (sources #{})]
    (testing (name source-type)
      (let [{:keys [execution names store signals colls loader-colls]} (run-source! source {:refuse? (refuse-chunk-of ["b"])})
            [_ chunks-coll phrases-coll] colls
            message (str (:pipeline-execution/error-message execution))]
        (is (= loader-colls colls)
            "PREMISE: the run's failure record changes no collection name")
        (is (= :completed (:pipeline-execution/status execution))
            (str "one refused document is within the budget: " message))
        (is (= 1 (:pipeline-execution/documents-failed execution)) "counted, exactly once")
        (is (= 2 (:pipeline-execution/documents-processed execution))
            "processed counts the documents STORED: the refused one is not among them")
        (is (= 1 (telemetry-failures signals)) "and exactly once by the live progress count too")
        (is (str/includes? message chunks-coll) (str "the persisted summary names the collection: " message))
        (is (str/includes? message "b-c2") "and the refused row's id")
        (is (str/includes? message "1 of 3") "and how many documents failed of how many")
        (is (= 1 (count (refused-events signals))) "one :pipeline/document-refused event")
        (is (= "b" (-> signals refused-events first :data :document-id)))
        (is (every? #(has? store chunks-coll (str % "-c1")) ["a" "c"]) "the other documents are stored")
        (is (not-any? #(has? store chunks-coll (str % "-old")) ["a" "c"])
            "PREMISE: their old revision was deleted, so the delete works")
        (is (has? store chunks-coll "b-old") "the refused document keeps its old chunks: no orphan delete ran")
        (is (has? store phrases-coll "b-oldp") "and its old phrases")
        (is (every? some? names) "a run that completes within its tolerance records its names")))))

(deftest the-same-runs-with-every-row-accepted-complete-cleanly
  (doseq [{:keys [source-type] :as source} (sources #{})]
    (testing (name source-type)
      (let [{:keys [execution names signals]} (run-source! source {})]
        (is (= :completed (:pipeline-execution/status execution))
            (str "PREMISE: the harness completes a clean run: " (:pipeline-execution/error-message execution)))
        (is (= 0 (:pipeline-execution/documents-failed execution)))
        (is (nil? (:pipeline-execution/error-message execution)) "no failures, no summary")
        (is (empty? (refused-events signals)))
        (is (every? some? names))))))

(deftest the-kudos-store-step-tolerates-a-refused-document
  ;; Through the executor the KUDOS loader is handed no `:stores` and writes
  ;; nothing (a separate issue). Its store step is driven directly, with the
  ;; Typesense store its own entry points configure.
  (let [store (atom {})
        kview {:tenant "t" :store/coll-prefix "t_" :stores #{{:store/type :typesense}}
               :documents/first-import-ids doc-ids :parallelism/documents 1 :parallelism/store 1
               :fault-tolerance/max-document-failures 10}
        [_ chunks-coll phrases-coll :as colls] (loader/coll-ids kview)]
    (seed-old-revisions! store colls doc-ids)
    (let [[_ signals]
          (with-signals
            #(with-redefs-fn
               (merge (fake-typesense store {:refuse? (refuse-chunk-of ["b"])})
                      {#'kudos/documents-by-ids (fn [_ ids] (m/seed (map (fn [id] {:id id}) ids)))
                       #'kudos/documents (fn [& _] (m/seed []))
                       #'loader/mk-prepare-document-t (fn [_ d] (m/sp (prepared (:id d))))
                       #'schema-drift/report! (fn [& _] nil)})
               (fn [] (try (m/? (loader/mk-materialize-t kview)) (catch Exception e e)))))]
      (is (= 1 (count (refused-events signals))) "the refusal is counted and named, not thrown out of the run")
      (is (every? #(has? store chunks-coll (str % "-c1")) ["a" "c"]) "the other documents are stored")
      (is (has? store chunks-coll "b-old") "the refused document keeps its old chunks")
      (is (has? store phrases-coll "b-oldp") "and its old phrases")
      (is (not (some #{"b"} @loader/!failed-documents)) "a refused document is not queued for a prepare retry"))))

;; =============================================================================
;; The budget, and that it is SHARED with prepare failures
;; =============================================================================

(deftest refusals-beyond-the-budget-fail-the-run
  (let [{:keys [execution names]} (run-source! folder {:refuse? (refuse-chunk-of ["b" "c"])}
                                               {:fault-tolerance/max-document-failures 2})
        message (str (:pipeline-execution/error-message execution))]
    (is (= :failed (:pipeline-execution/status execution)))
    (is (str/starts-with? message "failed: the document-failure budget (2) was reached") message)
    (is (= [nil nil nil] names) "a failed first run records no names")))

(deftest prepare-failures-and-refusals-share-one-budget
  (let [{:keys [execution]} (run-source! (first (sources #{"c"})) {:refuse? (refuse-chunk-of ["b"])}
                                         {:fault-tolerance/max-document-failures 2})]
    (is (= :failed (:pipeline-execution/status execution))
        "one prepare failure plus one refusal reaches a budget of 2")))

;; =============================================================================
;; The catch is narrow. Any other storage error fails the run at once
;; =============================================================================

(deftest any-other-storage-error-fails-the-run-immediately
  (let [{:keys [execution]}
        (run-source! folder {:explode? (fn [coll rows] (and (str/includes? coll "_chunks_")
                                                            (some #(= "b-c1" (:id %)) rows)))})
        message (str (:pipeline-execution/error-message execution))]
    (is (= :failed (:pipeline-execution/status execution))
        "a 500 is not a refusal: it fails the run with the budget (10) far from reached")
    (is (str/includes? message "Internal Server Error") (str "the run's error is the 500's own: " message))
    (is (not (str/includes? message "budget")) "not a budget failure")))

;; =============================================================================
;; No write may succeed silently: a KUDOS run with no store, and a run that stored nothing
;; =============================================================================

(deftest a-kudos-run-with-no-store-refuses-up-front
  ;; Through the executor the KUDOS loader is handed no `:stores`. It used to
  ;; "complete" having written nothing, and record names nothing wrote.
  (let [fetched (atom [])
        kudos-source {:source-type :kudos
                      :loader-keys {:documents/first-import-ids ["a" "b"]}
                      :redefs {#'kudos/documents-by-ids (fn [_ ids] (swap! fetched into ids) (m/seed (map (fn [id] {:id id}) ids)))
                               #'kudos/documents (fn [& _] (swap! fetched conj :documents) (m/seed []))
                               #'loader/mk-prepare-document-t (fn [_ d] (m/sp (prepared (:id d))))
                               #'schema-drift/report! (fn [& _] nil)}}
        {:keys [execution names]} (run-source! kudos-source {})
        message (str (:pipeline-execution/error-message execution))]
    (is (= :failed (:pipeline-execution/status execution)) (str "a store-less KUDOS run must fail: " message))
    (is (str/includes? message "no store") message)
    (is (= [nil nil nil] names) "and record no collection names")
    (is (= [] @fetched) "before anything is fetched"))
  (testing "the loader's other entry points refuse the same way"
    ;; Nothing here may reach Kudos or Typesense: a fetch, a prepare or a client call fails at once.
    (let [kview {:tenant "t" :store/coll-prefix "t_" :parallelism/documents 1 :parallelism/store 1}
          refuse-all (fn [what] (fn [& _] (throw (ex-info (str "must not " what) {}))))
          no-store? (fn [f] (try (f) false
                                 (catch clojure.lang.ExceptionInfo e (= :digdir.storage/no-store (:type (ex-data e))))))]
      (with-redefs-fn
        (merge (fake-typesense (atom {}) {})
               {#'kudos/documents-by-ids (refuse-all "fetch")
                #'kudos/documents (refuse-all "fetch")
                #'loader/mk-prepare-document-t (refuse-all "prepare")})
        (fn []
          (is (no-store? #(m/? (loader/mk-import-single-document-t kview "a"))))
          (let [before @loader/!failed-documents]
            (try (reset! loader/!failed-documents ["a"])
                 (is (no-store? #(loader/retry-failed-documents! kview)))
                 (finally (reset! loader/!failed-documents before)))))))))

(deftest a-run-that-stored-nothing-fails
  (let [{:keys [execution names]} (run-source! (first (sources #{} ["b"])) {:refuse? (refuse-chunk-of ["b"])})
        message (str (:pipeline-execution/error-message execution))]
    (is (= :failed (:pipeline-execution/status execution))
        (str "its one document refused, within the budget, the run still ingested nothing: " message))
    (is (str/includes? message "0 of 1 documents stored") message)
    (is (= [nil nil nil] names) "and records no collection names")))

(deftest an-empty-source-still-completes
  ;; The control for the test above: nothing seen is not nothing stored. (Website:
  ;; the folder loader has its own refusal of an empty corpus directory.)
  (let [{:keys [execution names]} (run-source! (second (sources #{} [])) {})]
    (is (= :completed (:pipeline-execution/status execution)) (str (:pipeline-execution/error-message execution)))
    (is (nil? (:pipeline-execution/error-message execution)))
    (is (every? some? names))))

(deftest a-kudos-run-reports-the-documents-it-stored
  ;; The executor's progress counts a stored document on `:pipeline/upserting-document`.
  ;; The KUDOS write path must emit it like the other loaders.
  (let [store (atom {})
        record (orchestration/failure-record)
        kview {:tenant "t" :store/coll-prefix "t_" :stores #{{:store/type :typesense}}
               :documents/first-import-ids ["a" "b" "c"] :parallelism/documents 1 :parallelism/store 1
               :fault-tolerance/max-document-failures 10 :fault-tolerance/failures record}
        [_ signals]
        (with-signals
          #(with-redefs-fn
             (merge (fake-typesense store {})
                    {#'kudos/documents-by-ids (fn [_ ids] (m/seed (map (fn [id] {:id id}) ids)))
                     #'kudos/documents (fn [& _] (m/seed []))
                     #'loader/mk-prepare-document-t (fn [_ d] (m/sp (prepared (:id d))))
                     #'schema-drift/report! (fn [& _] nil)})
             (fn [] (m/? (loader/mk-materialize-t kview)))))
        progress (reduce #'executor/update-progress-from-signal @#'executor/empty-progress
                         (filter (comp keyword? :id) signals))]
    (is (= 3 (:stored @record)) "PREMISE: the flow stored all three")
    (is (= 3 (:stored progress)) "and the run's progress says so: documents-processed counts them")))

;; =============================================================================
;; Only a DATA refusal is tolerated. An outage or a missing collection fails the run at once
;; =============================================================================

(defn- error-on-document-row-of [id type message]
  (fn [coll doc] (when (and (str/includes? coll "_documents_") (= id (:id doc))) [type message])))

(deftest an-outage-on-a-document-row-fails-the-run-at-once
  (let [{:keys [execution]} (run-source! folder {:doc-error (error-on-document-row-of
                                                             "b" :typesense.client/service-unavailable "Service Unavailable")})
        message (str (:pipeline-execution/error-message execution))]
    (is (= :failed (:pipeline-execution/status execution)) "a 503 is not a refusal: no budget applies")
    (is (str/includes? message "Service Unavailable") (str "the run's error is the client's own: " message))
    (is (not (str/includes? message "refused")) "and nothing reads it as refused")))

(deftest a-missing-collection-on-a-document-row-fails-the-run-at-once
  (let [{:keys [execution]} (run-source! folder {:doc-error (error-on-document-row-of
                                                             "b" :typesense.client/not-found "Not Found")})]
    (is (= :failed (:pipeline-execution/status execution)) "a 404 is not a refusal")))

(deftest a-bad-request-on-a-document-row-is-a-tolerated-refusal
  (let [{:keys [execution]} (run-source! folder {:doc-error (error-on-document-row-of
                                                             "b" :typesense.client/bad-request "Field `title` must be a string.")})]
    (is (= :completed (:pipeline-execution/status execution)) (str (:pipeline-execution/error-message execution)))
    (is (= 1 (:pipeline-execution/documents-failed execution)))
    (is (str/includes? (str (:pipeline-execution/error-message execution)) "refused"))))

;; =============================================================================
;; The persisted counts are the run's own, on the console's path too
;; =============================================================================

(deftest the-final-flush-does-not-overwrite-a-finished-runs-counts
  ;; Direct, so the order is certain: the run has finished and written its own
  ;; counts; telemetry, still in memory, counted differently.
  (it/with-test-db
    (fn []
      (let [conn (db/get-conn)
            id (executor/create-execution-record! conn "digdir:default:altinn-docs" "test-user")]
        (executor/update-execution-status! conn id :completed {:documents-processed 2 :documents-failed 0})
        (swap! executor/!executions-progress assoc id (assoc @#'executor/empty-progress :stored 3 :failures 1))
        (try
          (executor/flush-progress-to-db! conn id nil)
          (let [e (executor/get-execution @conn id)]
            (is (= 2 (:pipeline-execution/documents-processed e)) "the run's own processed count stays")
            (is (= 0 (:pipeline-execution/documents-failed e)) "and its failed count"))
          (finally (executor/forget-progress! id)))))))

(deftest a-console-run-persists-the-runs-own-failed-count
  ;; A 500 on a chunk write is not a document failure (the run fails), but the
  ;; live telemetry counts the store error. The record's count must be what stays.
  (let [{:keys [execution]} (run-source! folder {:explode? (fn [coll rows] (and (str/includes? coll "_chunks_")
                                                                                (some #(= "b-c1" (:id %)) rows)))}
                                         {} :async? true)]
    (is (= :failed (:pipeline-execution/status execution)) "PREMISE: the run failed")
    (is (= 0 (:pipeline-execution/documents-failed execution))
        "the persisted count is the run's (0), not the telemetry's")))

