(ns digdir.docs.loader
  {:clj-kondo/ignore true}
  (:require [digdir.rag.chunking :as document-chunking]
            [digdir.docs.retrieval-record :as rr]
            [digdir.docs.schema-drift :as schema-drift]
            [digdir.rag.typesense :as ts-utils]
            [clojure.string :as str]
            [digdir.llm.kudos :as kudos]
            [typesense.client :as ts]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [net.cgrand.xforms.io :as xfio]
            [net.cgrand.xforms.rfs :as rfs]
            [missionary.core :as m]
            [clojure.java.io :as jio]
            [digdir.docs.file-fetch :as file-fetch]
            [digdir.llm.marker :as marker]
            [duratom.core :refer [duratom]]
            [clojure.java.shell :refer [sh]]
            [digdir.llm.client :as openai]
            [taoensso.telemere :as t]
            [valuehash.api]
            [digdir.util.core :refer :all]
            [taoensso.encore :refer [takev]]
            [net.cgrand.xforms :as xfs]
            [tick.core :as tick]
            [hyperfiddle.rcf :refer [tests tap %]]
            [clojure.java.io :as jio]
            [medley.core :as y]
            [clojure.set :as set]
            [lambdaisland.deep-diff2 :as ddiff]
            [digdir.config.accessor :as cfg]
            [digdir.docs.pipeline.core :as pcore]))

(sh "mkdir" "-p" "state")

(declare sha256-short-hash)

#_(t/add-handler! :telemetry (t/handler:file {:output-fn (t/pr-signal-fn)}))

(defonce !telemetry-aggregate (fileatom "state/telemetry-aggregate.duratom" {}))

(defonce !transient-telemetry-aggregate (atom {}))

(def telemetry-aggregator (fn [agg sig]
                            (update-in agg [:id-counts (:id sig)] (fnil inc 0))))

;; !telemetry-window contains raw signals and as such can't be serialized.
(defonce !signal-window (atom '()))

(t/add-handler! ::signal-handler
                (fn
                  ([signal]
                   ;; Do I need to return the signal?
                   (swap! !telemetry-aggregate telemetry-aggregator signal)
                   (swap! !transient-telemetry-aggregate telemetry-aggregator signal)

                   (swap! !signal-window #(take 400 (conj % (-> signal
                                                                (update :msg_ force)
                                                                ;; Instants and the Throwable in :error have no
                                                                ;; wire encoding in Electric, so the UI reads
                                                                ;; these signals as strings.
                                                                (update :inst str)
                                                                (update :end-inst str)
                                                                (update :error str))))))
                  ;; Shutdown handler
                  ([])))

(comment
  (xfs/transjuxt
   {:window (comp
             (map inc)
             (xfs/into [])
             (map reverse))}
   [1 2 3]))

(defn worth-retrying? [error]
  (let [check-chain (fn check-chain [e]
                      (when e
                        (if (contains? (ex-data e) :worth-retrying)
                          (:worth-retrying (ex-data e))
                          (check-chain (ex-cause e)))))]
    (not= false (check-chain error))))

(defn backoff [request delays]
  (if-some [[delay & delays] (seq delays)]
    (m/sp
     (try (let [r (m/? request)]
            (t/event! :backoff/generic-success)
            r)
          (catch Exception error
            (t/event! :backoff/generic-retry-i-think-the-other-one-is-not-working)
            (t/error! {:id :backoff/generic-retry
                       :data {:delay delay}
                       :msg ["Waiting" delay "ms"]}
                      error)
            (if (worth-retrying? error)
                (do (m/? (m/sleep delay))
                    (m/? (backoff request delays)))
                (throw error)))))
    request))

(defmacro thread
  "Runs body in a new thread. Exceptions will propagate to the uncaught
  exception handler. Prefer this to future, if you don't need to deref
  it."
  [& body]
  `(doto (Thread. (fn [] ~@body))
     (.start)))

(defn say [msg]
  (t/event! :say {:data {:msg msg}})
  ;; The audio feedback is useful for debugging, but coreaudiod might
  ;; crash your computer if there's too many voice messages 😅
  #_(thread (try (sh "say" msg) (catch Exception e)))
  nil)

(defonce !duratom-store (duratom :local-file
                                 :file-path "state/knowledge.duratom"
                                 :init {}))

(defn => [& fns] (apply comp (reverse fns)))

(defn run-task-async [task]
  (task
   (fn [success] (t/event! :run-task-async-success {:data success}))
   (fn [failure] (t/error! :run-task-async-failure failure))))

(defn run-flow-async
  ([f]
   (run-flow-async f (constantly nil)))
  ([f rf]
   ((m/reduce rf f)
    (fn [success] (t/event! :run-flow-async-success {:data success}))
    (fn [failure] (t/error! :run-flow-async-failure failure)))))


(defn ^:vibed? split-url-extension [url-str]
  "Splits URL into [main-part extension]. Extension is nil if none exists."
  (let [url (java.net.URL. url-str)
        path (.getPath url)
        last-dot (.lastIndexOf path ".")]
    (if (> last-dot 0)
      (let [path-without-ext (subs path 0 last-dot)
            extension (subs path last-dot)
            main-url (str (.getProtocol url) "://" (.getAuthority url) path-without-ext)]
        [main-url extension])
      [url-str nil])))

(tests
 (split-url-extension "https://camo.githubusercontent.com/9/7")
 :=
 ["https://camo.githubusercontent.com/9/7" nil]

 (split-url-extension "https://camo.githubusercontent.com/9/7.pdf")
 :=
 ["https://camo.githubusercontent.com/9/7" ".pdf"])

(def url-extension (=> split-url-extension last))

(defn extract-year-from-date
  "Extract year as integer from a date string like '2023-05-15' or '2023'."
  [date-str]
  (when (and date-str (string? date-str) (>= (count date-str) 4))
    (try
      (Integer/parseInt (subs date-str 0 4))
      (catch Exception _ nil))))

(defn fill-in-doc-fields [doc]
  (-> doc
      (update :id str)
      (assoc :doc_num (str (:id doc)))
      (assoc :concerned_years
             (vec
              (cond
                (and (:concerned_year_from doc) (:concerned_year_to doc))
                (range (:concerned_year_from doc)
                       (inc (:concerned_year_to doc)))

                (:concerned_year_from doc)
                [(:concerned_year_from doc)]

                (:concerned_year_to doc)
                [(:concerned_year_to doc)]

                (:concerned_year doc)
                [(:concerned_year doc)]

                ;; No concerned-year information: emit nothing (#238).
                ;;
                ;; This used to substitute the publish year. That fabricated a
                ;; value indistinguishable from a real one - nothing downstream
                ;; could tell a derived year from a registered one - and it was
                ;; wrong for 216 of the 2,638 documents whose title states a
                ;; single differing year. The property removed here is
                ;; FABRICATION WITHOUT PROVENANCE, not the wrong values: a
                ;; correction pass alone would be overwritten, because this fn
                ;; recomputes the field on every upsert.
                ;;
                ;; Deriving a year from elsewhere cannot be right in general
                ;; either. 29% of the ground truth in #228 was a range or
                ;; several years, so no single-scalar fallback can be correct
                ;; for those however it is computed. A value that cannot be
                ;; right 29% of the time should not be invented at all; an
                ;; empty list is the honest answer and it makes the gap
                ;; countable, which it previously was not.
                :else
                [])))))

(defn mk-require-url-file
  "Download file from URL to a temp file.
  Returns the path to the temp file."
  [url]
  (m/via m/blk
         (let [ext (url-extension url)
               temp-file (java.io.File/createTempFile "pdf-" ext)]
           (t/event! :require-url
                     {:data {:url url
                             :temp-path (.getAbsolutePath temp-file)}})
           (with-open [in (jio/input-stream url)
                       out (jio/output-stream temp-file)]
             (jio/copy in out))
           (.getAbsolutePath temp-file))))

(defn sha256-short-hash
  "Second copy of `digdir.docs.pipeline.core/sha256-short-hash`, used by the
   legacy KUDOS ingest path. Same 12-character truncation, so the same 48-bit
   collision bound applies — see that docstring; phrases are the binding
   keyspace, not chunks."
  [v]
  (->> v valuehash.api/sha-256-str (take 12) (apply str)))

(defn exponential-backoff [n]
  ;; randomization to avoid bursts
  (take n
        (map #(+ % (* % (rand 0.5)))
             (iterate (partial * 4) 1000))))

(defonce !pdfium-error-count (atom 0))

(defn pdfium-data-format-error? [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (= 500 (:status (ex-data e)))
       (some-> (ex-data e) :body (str/includes? "PDFium: Data format error"))))

(defn pdf->md
  "Convert PDF to markdown using Marker service API.
  Caching is handled server-side by the Marker API."
  [tenant filename]
  (try
    (marker/->md tenant filename)
    (catch clojure.lang.ExceptionInfo e
      (if (pdfium-data-format-error? e)
        (do
          (swap! !pdfium-error-count inc)
          (t/event! :document-loading/pdfium-data-format-error
                    {:data {:count @!pdfium-error-count
                            :filename filename}})
          (throw (ex-info "PDFium data format error - not retrying"
                          {:worth-retrying false
                           :filename filename}
                          e)))
        (throw e)))))

(defn fixed-width-chunks [{:chunks.fixed-width/keys [width overlap unit]
                           #_#_:or {:width 173
                                    :overlap 20
                                    :unit :character}} text]
  #_(t/log! ["Fixed width chunking" width overlap unit])
  ;; No :chunk_id here — see pcore/chunk-id. chunk-doc assigns ids once it
  ;; knows which document the text belongs to.
  (mapv (fn [c] {:content_markdown (str/join c)})
        (partition-all width (- width overlap) text)))

(defn header-based-chunks [kview text]
  #_(t/log! ["Header-based chunking"])
  (let [doc {:page-content text}]
    (->> (document-chunking/split-into-chunks-by-headers kview [doc])
         (mapv (fn [{:keys [page-content metadata]}]
                 ;; No :chunk_id here — see pcore/chunk-id.
                 {:content_markdown page-content
                  :metadata (prn-str metadata)})))))

(defn chunk-doc [{:as kview
                  :chunks/keys [strategy minimum-length maximum-length]}]
  (fn [doc]
    (t/event! :document-loading/chunking-document {:data {:id (:id doc)
                                                          :strategy strategy}})
    (let [all-chunks (mapcat
                      (fn [file]
                        (let [md (pdf->md (:tenant kview) (:path file))]
                          (case strategy
                            :fixed-width (fixed-width-chunks kview md)
                            :header-based (header-based-chunks kview md))))
                      (:files doc))
          filtered-chunks (filter (fn [chunk]
                                    (let [l (count (:content_markdown chunk))]
                                      (cond
                                        (< l minimum-length)
                                        (do
                                          (t/event! :document-loading/CHUNK-FILTERED-BECAUSE-TOO-SHORT)
                                          false)

                                        (> l maximum-length)
                                        (do
                                          (t/event! :document-loading/CHUNK-FILTERED-BECAUSE-TOO-LONG)
                                          false)

                                        :else true)))
                                  all-chunks)]
      (assoc doc :chunks
             (->> filtered-chunks
                  ;; Ids are derived here, not in the chunker: this is the
                  ;; first point that knows the document. See pcore/chunk-id.
                  (pcore/assign-chunk-ids (:doc_num doc))
                  (map-indexed (fn [index chunk]
                                 (assoc chunk
                                        :doc_num (:doc_num doc)
                                        :chunk_index index
                                        :content_length (count (:content_markdown chunk)))))
                  vec)))))

(defn extract-ns-from-map [m ns]
  (into {}
        (filter (=> key namespace #(or (str/starts-with? % (str ns ".")) (= ns %))))
        m))

(defn kview-hash [kview ns]
  (sha256-short-hash
   (extract-ns-from-map
    (select-keys [:hash-changer :strategy] kview) ns)))

(defn coll-ids [{:store/keys [coll-prefix] :as kview}]
  [(str coll-prefix "documents_" (kview-hash kview "documents"))
   (str coll-prefix "chunks_" (kview-hash kview "chunks"))
   (str coll-prefix "phrases_" (kview-hash kview "search-phrases"))])

(def ts-admin ts-utils/ts-admin)

(defn extract-display-names
  "Extracts names with type 'display' from a collection of organizations.
   Each organization should have an :alternative_names key containing name variants."
  [orgs]
  (->> orgs
       (mapcat :alternative_names)
       (filter #(= "display" (:type %)))
       (map :name)))

(defn document-inserted?
  "Check if a document is already inserted in the TypeSense documents collection.
   
   Two arities:
   - (document-inserted? kview doc) - uses global kview to determine collection
   - (document-inserted? kview documents-coll doc) - uses explicit collection name"
  ([kview doc]
   ;; Version that uses global kview (fallback)
   (let [[documents-coll _ _] (coll-ids kview)]
     (document-inserted? kview documents-coll doc)))
  ([kview documents-coll doc]
   ;; Version that takes collection name explicitly
   (try
     (ts/retrieve-document ts-admin documents-coll (:id doc))
     true ; document exists if no exception thrown
     (catch Exception _
       false))))

(comment :vibed
  ;; Testing document-inserted? function

  ;; Get the documents collection name
         (def test-documents-coll (first (coll-ids kview)))
         test-documents-coll

  ;; Find an existing document ID to test with
         (def sample-search (ts/search ts-admin test-documents-coll {:q "*" :query_by "" :per_page 1}))
         (def existing-doc-id (get-in sample-search [:hits 0 :document :id]))
         existing-doc-id

  ;; Test with existing document (should return true)
         (document-inserted? kview test-documents-coll {:id existing-doc-id})

  ;; Test with non-existing document (should return false)
         (document-inserted? kview test-documents-coll {:id "definitely-does-not-exist-12345"})

  ;; Test single-arity version with existing document
         (document-inserted? kview {:id existing-doc-id})

  ;; Test single-arity version with non-existing document
         (document-inserted? kview {:id "another-non-existent-id-67890"})

  ;; Test edge cases
         (document-inserted? kview test-documents-coll {:id nil}) ; should return false
         (document-inserted? kview test-documents-coll {}) ; should return false
         (document-inserted? kview test-documents-coll {:id ""}) ; should return false

  ;; Check how many documents are in the collection
         (def collection-stats (ts/search ts-admin test-documents-coll {:q "*" :query_by "" :per_page 0}))
         (:found collection-stats)

  ;; Test the original problematic behavior by directly calling ts/retrieve-document
  ;; This should return the document data when it exists
         (ts/retrieve-document ts-admin test-documents-coll existing-doc-id)

  ;; This should throw an exception when document doesn't exist
         (try
           (ts/retrieve-document ts-admin test-documents-coll "non-existent-id")
           (catch Exception e
             {:caught-exception true
              :message (.getMessage e)
              :type (type e)}))

  ;; Verify the fix works in the context it will be used
         (let [[documents-coll chunks-coll phrases-coll] (coll-ids kview)
               test-doc {:id existing-doc-id}]
           {:collection documents-coll
            :doc-exists? (document-inserted? kview documents-coll test-doc)
            :should-skip-upsert? (document-inserted? kview documents-coll test-doc)})) ; document doesn't exist if exception is thrown 

(defn file-digests
  "The distinct sha256 digests of a document's files, as a FLAT vector.

   The digests already exist inside :files, but :files is an array of objects
   and the documents collection is created with enable_nested_fields false - a
   setting Typesense will not let you change on an existing collection, so
   `files.sha256` cannot be made filterable without dropping and recreating the
   collection (#228).

   A flat sibling field sidesteps that entirely. It costs nothing to write, and
   because Typesense indexes values that are ALREADY STORED when a field is
   later declared, it can be turned into a real lookup by one schema PATCH -
   measured at 0.91s over 11,306 documents, with no reindex and no re-upsert.
   Writing it now is what makes that PATCH cheap later; until then it is simply
   stored, exactly as :concerned_year and :publish_date already are."
  [doc]
  (vec (distinct (keep :sha256 (:files doc)))))

(defn indexable-files
  "A document's files with the ingest-host scratch path removed (#253).

   :path is assoc'd during loading by mk-require-url-file and points at a
   java.io.File/createTempFile on whichever machine ran the ingest. It is
   deleted when that run finishes, so by the time anyone can read it out of the
   index it has ALWAYS been wrong - and it sits beside size, pages, sha256 and
   mimetype, every one of which is real, with nothing marking it as scratch.

   Dropped from the projection rather than from the pipeline: :path is load
   bearing DURING ingest (loader.clj passes it to pdf->md), it is only the
   stored copy that is meaningless."
  [doc]
  (mapv #(dissoc % :path) (:files doc)))

(defn prepare-doc [doc]
  (let [all-orgs (concat (:owners doc) (:recipients doc) (:publishers doc))
        display-names (vec (distinct (extract-display-names all-orgs)))]

    (select-keys (assoc doc
                        :orgs_long display-names
                        :files (indexable-files doc)
                        :file_sha256 (file-digests doc)
                        :total_chunks (count (:chunks doc)))
                 [:id :doc_num :title :authors :orgs_long
                  ;; The two range fields are the FIRST branches of
                  ;; fill-in-doc-fields' cond, and they are what a multi-year
                  ;; document uses - 29% of ground-truth values in #228 were
                  ;; ranges or multiple years, not single years. Dropping them
                  ;; here left the collection unable to distinguish a
                  ;; range-supplied concerned_years from a fabricated one,
                  ;; which is why #238's population could only be found by
                  ;; comparing against titles. Restoring them is a
                  ;; prerequisite for measuring that population, not a
                  ;; convenience. They are stored-but-not-indexed: the docs
                  ;; schema declares neither, and Typesense keeps and returns
                  ;; unknown fields - :concerned_year and :publish_date have
                  ;; been riding along undeclared for the same reason.
                  :concerned_year :concerned_year_from :concerned_year_to
                  :files :file_sha256 :publish_date :type :language
                  :concerned_years :total_chunks
                  ;; #308 C. Computed by mk-prepare-document-t from the
                  ;; previously indexed document; they must be listed here
                  ;; or upsert destroys them on the next run.
                  :last_retrieval_success_at :first_retrieval_failure_at
                  :last_retrieval_failure_at :consecutive_retrieval_failures
                  :retrieval_attempts])))

(defn- delete-orphans!
  "Delete docs in `coll` for `doc-num` whose Typesense `:id` is NOT
   in `current-ids`. Mirrors
   digdir.docs.pipeline.storage/delete-orphan-{chunks,phrases}! so
   the kudos loader gets the same cleanup. Filters on `id`, not
   `chunk_id`, to catch legacy auto-id rows from before chunks/phrases
   carried an explicit `:id`."
  [coll doc-num current-ids]
  (when (and (seq current-ids) (not (str/blank? (str doc-num))))
    (let [filter-by (str "doc_num:=" doc-num
                         " && id:!=[" (str/join "," current-ids) "]")]
      (t/event! :document-loading/deleting-orphans
                {:data {:coll coll
                        :doc-num doc-num
                        :keep-count (count current-ids)}})
      (ts/delete-documents! ts-admin coll {:filter_by filter-by}))))

(defn store-doc [store kview]
  (let [[documents-coll chunks-coll phrases-coll] (coll-ids kview)]
    (case (:store/type store)
      ;; Always upserts; orphan-cleanup after each batch. See
      ;; digdir.docs.pipeline.storage/store-complete-document! for the
      ;; long-form explanation of why we pin :id on chunks/phrases and
      ;; why the delete filters on :id rather than :chunk_id.
      :typesense (fn [doc]
                   (let [chunks (mapv #(-> %
                                           (dissoc :search-phrases :embedding)
                                           (assoc :id (:chunk_id %)))
                                      (:chunks doc))
                         current-chunk-ids (mapv :id chunks)
                         phrases (mapcat (fn [c]
                                           (for [phrase (:search-phrases c)]
                                             {:id (sha256-short-hash (str (:chunk_id c) "|" phrase))
                                              :search_phrase phrase
                                              :chunk_id (:chunk_id c)
                                              :doc_num (:doc_num doc)}))
                                         (:chunks doc))
                         current-phrase-ids (mapv :id phrases)]
                     (t/event! :document-loading/upserting-to-documents-typesense-collection)
                     (ts/upsert-document! ts-admin documents-coll (prepare-doc doc))
                     (t/event! :document-loading/upserting-to-chunks-typesense-collection
                               {:data {:chunk-count (count chunks)}})
                     (ts/upsert-documents! ts-admin chunks-coll chunks)
                     (delete-orphans! chunks-coll (:doc_num doc) current-chunk-ids)
                     (t/event! :document-loading/upserting-to-phrases-typesense-collection)
                     (ts/upsert-documents! ts-admin phrases-coll phrases)
                     (delete-orphans! phrases-coll (:doc_num doc) current-phrase-ids)
                     (say "Stored")
                     (t/event! :document-loading/document-upserted)))

      :dev/duratom (fn [doc]
                     (swap! !duratom-store update documents-coll (fnil conj #{}) doc)))))

(defn mk-store-document-in-store-t [store kview doc]
  (m/sp
    (m/? (m/via m/blk ((store-doc store kview) doc)))))

(defn mk-store-document-t [kview doc]
  (apply m/join vector
         (map #(mk-store-document-in-store-t % kview doc)
              (:stores kview))))

(defonce !store-threads (atom 0))
(defn mk-store-documents-f [kview documents]
  (m/ap (let [doc (m/?> (:parallelism/store kview) documents)
              _ (swap! !store-threads inc)
              doc (m/? (mk-store-document-t kview doc))
              _ (swap! !store-threads dec)]
          doc)))

(comment
  (t/with-signal
    (m/?
     (mk-require-url-file "https://github.com/olavfosse.png"))))

(defn mk-require-file
  "Download an indexed `files[]` entry's binary to a temp file; return the path.

   Goes through `file-fetch/fetch-file!`, which hops to the landing page's
   `citation_pdf_url` when the indexed url serves HTML, and verifies the bytes
   against the entry's `sha256`/`size` before returning them (#239). Replaces
   the url-only fetch, which handed whatever came back straight to Marker."
  [file]
  (m/via m/blk
         (let [ext (url-extension (:url file))
               temp-file (java.io.File/createTempFile "pdf-" ext)]
           (t/event! :require-file
                     {:data {:url (:url file)
                             :temp-path (.getAbsolutePath temp-file)}})
           (with-open [out (jio/output-stream temp-file)]
             (jio/copy (file-fetch/fetch-file! file) out))
           (.getAbsolutePath temp-file))))

(defn indexed-document
  "The document as currently indexed, or nil when it is not indexed yet.

   Needed because the writer uses Typesense `action=upsert`, which replaces the
   whole document: anything not in `prepare-doc`'s payload is destroyed. The
   retrieval-record fields therefore have to be read and carried forward (#308)."
  [documents-coll doc]
  (try
    (ts/retrieve-document ts-admin documents-coll (:id doc))
    (catch Exception _ nil)))

(defn mk-require-doc-files-t
  "Download the document's files, or record that they are unreachable.

   A file that is deterministically unreachable is a state of the world, not a
   failed attempt, so it does not throw: the document comes back tagged
   `::unreachable` and continues through the pipeline to be written with its
   retrieval record (#308 B and C).

   The discriminator is the one `worth-retrying?` already uses, which #320
   established for the retry ladder. That is deliberate — \"re-requesting cannot
   change this\" and \"this is not a fault\" are the same judgement, and letting
   them drift apart would mean a 404 that stops being retried but still aborts
   the run, or the reverse.

   Genuine faults — a digest mismatch, a hop that yields non-PDF, a timeout —
   still throw and still consume the failure budget, which is what it is for."
  [kview doc]
  (m/sp
   (try
     (let [paths (m/? (apply m/join vector (map mk-require-file (:files doc))))
           files (mapv #(assoc %1 :path %2) (:files doc) paths)]
       (assoc doc :files files))
     (catch Exception e
       (if (worth-retrying? e)
         (throw e)
         (do
           (t/event! :document-loading/file-unreachable
                     {:data {:doc-id (:id doc) :reason (ex-message e)}})
           (assoc doc ::unreachable (ex-message e) :files [])))))))

(defn mk-chunk-doc-t [kview doc]
  (m/via m/blk ((chunk-doc kview) doc)))

;; Let's say we are processing n documents in parallell and each doc
;; has multiple stages of processing that it may be in, and each stage
;; may in turn have several concurrent branches, cleaning up this
;; process is complicated, and scheduling it on the right threads is
;; too, this is what Missionary handles for us.
;;
;; So don't be dogmatic about it, but consider using Missionary for
;; big processes, for small single-threaded side effects there's not
;; that much to gain, so don't put in energy and lines to using
;; Missionary in leafs where it doesn't actually matter.
(defn openai-implementation
  [tenant impl]
  (case impl
    :lm-studio {:api-endpoint "http://localhost:1234/v1"
                :request {:timeout 300000}}
    #_#_:runpod {:api-endpoint "https://fboqsdcxlh2yxt-8000.proxy.runpod.net/v1"
                 :request {:timeout 30000}}
    :azure-openai {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
                   :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
                   :impl :azure
                   :request {:timeout 30000}
                   ;;  :trace (fn [request response]
                   ;;           #_(println "Request:" request)
                   ;;           (println "Response:" response))
                   }
    :openrouter {:api-key (cfg/get {:tenant tenant} :services :openrouter :api-key)
                 :api-endpoint "https://openrouter.ai/api/v1"
                 :request {:timeout 30000}}
    (throw (ex-info "Unknown OpenAI implementation" {:impl impl}))))
;; azure-openai

(defn create-chat-completion [tenant conversation]
  ;; Idea is to dispatch to the right provider based on the model name
  (if (#{:google/gemma-3-27b-it :google/gemma-3-12b-it :dphn/Dolphin-Mistral-24B-Venice-Edition} (:model conversation))
    (openai/create-chat-completion conversation
                                   (openai-implementation tenant :openrouter))
    (openai/create-chat-completion
     (assoc conversation
            :model (cfg/get {:tenant tenant} :services :azure-openai :deployment-name))
     (openai-implementation tenant :azure-openai))))

(defn mk-distill-search-phrases-t [{:search-phrases/keys [model fallback-model prompt] :as kview} chunk]
  (m/via m/blk
         ;; Keyed on content, not chunk_id: the phrases depend only on the
         ;; chunk text, and document-scoped ids (#72) would otherwise cost one
         ;; LLM call per copy of every duplicated chunk. Mirrors
         ;; digdir.docs.pipeline.search-phrases/cache-key.
         (let [cache-key (str (sha256-short-hash (:content_markdown chunk)) "-" (sha256-short-hash model) "-" (sha256-short-hash prompt))
               cache-dir "cache/search-phrases/"
               cache-path (str cache-dir cache-key ".edn")
               file (jio/file cache-path)]

           (when-not (java.io.File/.exists (jio/file cache-dir))
             (jio/make-parents cache-path))

           (if (java.io.File/.exists file)
             (do
               (t/event! :document-loading/search-phrases-distillation-cache-hit)
               (let [cached-phrases (edn/read-string (slurp file))]
                 (assoc chunk :search-phrases cached-phrases)))

             (do
               #_(say "BE WARE: CACHE MISS, CALLING OUT TO OPEN ARTIFICIAL INTELLIGENCE GEE PEE TEE 4 o")
              (t/event! :document-loading/search-phrases-distillation-cache-miss
                         #_{:data {:chunk chunk}})
               (let [tenant (:tenant kview)
                     generate-with-model (fn [model]
                                           (->
                                            (let [convo {:model model
                                                         :messages [{:role "user"
                                                                     :content (str/replace prompt "REPLACE_ME" (:content_markdown chunk))}]}]
                                              #_(t/log! ["Generating search phrases with model" model])
                                              (create-chat-completion tenant convo))
                                            :choices
                                            first
                                            :message
                                            :content
                                            str/split-lines
                                            last
                                            (str/split #",")
                                            ((partial mapv str/trim))))
                     search-phrases
                     (try (generate-with-model model)
                          (catch Exception e
                            (t/error! {:id :document-loading/search-phrases-distillation-error
                                       :msg ["Generating search phrases with :search-phrases/model" model "failed"]} e)
                            (t/log! {:id :document-loading/search-phrases-distillation-with-fallback-model}
                                    ["Generating search phrases with :search-phrases/fallback-model" fallback-model])
                            (generate-with-model fallback-model)))
                     result-chunk (assoc chunk :search-phrases search-phrases)]

           ;; Cache the result
                 (spit file (pr-str search-phrases))

                 (t/event! :document-loading/distill-chunk-search-phrases #_{:data result-chunk})
                 result-chunk))))))

(defn mk-distill-doc-search-phrases-t [kview doc]
  (m/sp
    (assoc doc :chunks
           (m/? (apply m/join
                       vector
                       (map (partial mk-distill-search-phrases-t kview)
                            (:chunks doc)))))))

(comment
  (thread (def result-doc' (m/? (mk-distill-doc-search-phrases-t kview result-doc)))))



(defn mk-prepare-document-t
  "Prepare one document, recording whether its file could be retrieved.

   An unreachable document skips chunking and phrase distillation — there is no
   content to chunk — but is still prepared and still written, carrying the
   retrieval record that says why it has none (#308 C). That is the whole
   difference between a document we cannot fetch and a document we have
   forgotten about."
  [kview doc]
  (m/sp
   #_(m/? (m/sleep 10000))
   (let [[documents-coll _ _] (coll-ids kview)
         prior (indexed-document documents-coll doc)
         now (quot (System/currentTimeMillis) 1000)
         doc (m/? (mk-require-doc-files-t kview (fill-in-doc-fields doc)))
         unreachable? (contains? doc ::unreachable)
         doc (if unreachable?
               (assoc doc :chunks [] :search_phrases [])
               (as-> doc d
                 (m/? (mk-chunk-doc-t kview d))
                 (m/? (mk-distill-doc-search-phrases-t kview d))))]
     (say (if unreachable? "Prepared (file unreachable)" "Prepared"))
     (t/event! (if unreachable?
                 :document-loading/document-prepared-unreachable
                 :document-loading/document-prepared))
     (merge doc (rr/observe prior
                            (if unreachable? :unreachable :success)
                            now)))))

#_(Vibe "render these documents in a nice way and implement affordances to call the callbacks"
        documents
        {:delete-document (e/fn [doc-id ...])})

(defonce !failed-documents (atom []))

(defonce !terminal-failure? (atom false))

(defn mk-prepare-documents-f [kview documents-f]
  (let [[documents-coll _ _] (coll-ids kview)]
    (m/ap (let [prepare-document-failures (atom 0)
                doc (m/?> (:parallelism/documents kview) documents-f)]
            ;; Skip already imported documents if :skip-already-imported is true
            (if (and (:skip-already-imported kview)
                     (document-inserted? kview documents-coll doc))
              (do
                (t/event! :document-loading/skipping-already-imported-document
                          {:data {:doc-id (:id doc)}})
                (m/amb))
              (try
                (t/event! :document-loading/handling-document)
                (m/? (backoff (mk-prepare-document-t kview doc)
                              [(+ (* 1 60 1000) (rand-int (* 59 60 1000)))]))
                (catch Exception e
                  (let [doc-id (:id doc)
                        failures (swap! prepare-document-failures inc)
                        terminal? (= failures (:fault-tolerance/max-document-failures kview))]
                    (swap! !failed-documents conj doc-id)
                    (t/event! :document-loading/failed {:data {:doc-id doc-id}})

                    (if terminal?
                      (say (str "FATAL: " failures " documents failed to import. Shutting down"))
                      (say (str "WARNING: " failures " documents failed to import. Skipping document")))

                    (t/error! {:id (if terminal?
                                     :document-loading/TERMINAL-PREPARE-DOCUMENT-FAILURE
                                     :document-loading/non-terminal-prepare-document-failure)
                               :data {:failures failures
                                      :doc-id doc-id}}
                              e)
                    (when terminal?
                      (reset! !terminal-failure? true)
                      (throw e))
                    (m/amb)))))))))

(def eval-ns *ns*)
(defn mk-filter-documents-f [kview documents]
  (let [{:documents/keys [types limit offset]
         :or {types (constantly true)
              limit 1
              offset 0}} kview]
    (t/log! ["offset = " offset])
    (m/eduction (filter (=> :type types))
                (y/distinct-by :id)
                (binding [*ns* eval-ns]
                  (t/event! :debug/evalling)
                  (let [x 
                        (eval (:documents/transducer kview))]
                    (t/event! :debug/evaled)
                    x))
                (drop offset)
                (take limit)

                documents)))

(defn create-docs-coll [store name]
  (try
    #_(t/event! :document-loading/creating-docs-collection)
    (ts/create-collection!
     ts-admin
     (assoc (edn/read-string (slurp (jio/resource "docs_schema.edn")))
            :name name))
    (catch clojure.lang.ExceptionInfo e
      (if (= (:type (ex-data e)) :typesense.client/conflict)
        (do
          #_(t/log! ["Collection already exists, moving on --" name])
          :already-exists)
        (throw e)))))
(defn kudos-chunk-typesense-schema [[docs-collection-name chunks-collection-name :as coll-ids]]
  {:name chunks-collection-name
   :fields [;; Like the original chunks schema, with some things omitted
            {:facet true
             :index true
             :infix false
             :locale ""
             :name "chunk_id"
             :optional false
             :sort true
             :stem false
             :store true
             :type "string"}
            {:facet true
             :index true
             :infix false
             :locale ""
             :name "doc_num"
             :optional false
             :reference (str docs-collection-name ".doc_num")
             :async_reference true
             :sort false
             :stem false
             :store true
             :type "string"}
            {:facet true
             :index true
             :infix false
             :locale ""
             :name "chunk_index"
             :optional false
             :sort true
             :stem false
             :store true
             :type "int32"}
            {:facet false
             :index true
             :infix false
             :locale "no"
             :name "content_markdown"
             :optional false
             :sort false
             :stem false
             :store true
             :type "string"}
            {:facet false
             :index true
             :infix false
             :locale "no"
             :name "metadata"
             :optional true
             :sort false
             :stem false
             :store true
             :type "string"}
            {:facet false
             :index true
             :name "content_length"
             :optional true
             :sort true
             :type "int32"}
            #_{:facet true
               :index true
               :infix false
               :locale ""
               :name "url"
               :optional false
               :sort false
               :stem false
               :store true
               :type "string"}
            #_{:facet true
               :index true
               :infix false
               :locale ""
               :name "url_without_anchor"
               :optional false
               :sort false
               :stem false
               :store true
               :type "string"}
            #_{:facet true
               :index true
               :infix false
               :locale "en"
               :name "type"
               :optional false
               :sort false
               :stem false
               :store true
               :type "string"}
            #_{:facet false
               :index true
               :infix false
               :locale ""
               :name "item_priority"
               :optional false
               :sort true
               :stem false
               :store true
               :type "int64"}
            #_{:facet true
               :index true
               :infix false
               :locale ""
               :name "updated_at"
               :optional false
               :sort true
               :stem false
               :store true
               :type "int64"}
            #_{:facet true
               :index true
               :infix false
               :locale ""
               :name "language"
               :optional false
               :sort false
               :stem false
               :store true
               :type "string"}
            #_{:facet false
               :index true
               :infix false
               :locale ""
               :name "markdown_checksum"
               :optional true
               :sort false
               :stem false
               :store true
               :type "string"}
            #_{:facet true
               :index true
               :infix false
               :locale ""
               :name "token_count"
               :optional true
               :sort true
               :stem false
               :store true
               :type "int64"}]
   ;; :default_sorting_field "chunk_index"
   })

(defn create-chunks-coll [store kview]
  (let [[docs-coll chunks-coll :as ids] (coll-ids kview)]
    (try
      #_(t/log! ["Creating collection" chunks-coll])
      (ts/create-collection! ts-admin
                             (kudos-chunk-typesense-schema ids))
      (catch clojure.lang.ExceptionInfo e
        (if (clojure.core/= (:type (ex-data e)) :typesense.client/conflict)
          (do
            #_(t/log! ["Collection already exists, moving on --" chunks-coll])
            :already-exists)
          (throw e))))))

(defn kudos-phrases-typesense-schema
  [[docs-collection-name chunks-collection-name phrases-collection-name :as coll-ids]]
  {:default_sorting_field "chunk_id"
   ;;  :enable_nested_fields false
   :fields
   [{:facet true, :index true, :infix false, :locale "", :name "chunk_id", :optional false, :sort true, :stem false, :stem_dictionary "", :store true, :type "string"}
    {:async_reference false, :facet true, :index true, :infix false, :locale "", :name "doc_num", :optional false,
     :reference (str docs-collection-name ".doc_num"), :sort true, :stem false, :stem_dictionary "", :store true, :type "string"}
    {:facet false, :index true, :infix false, :locale "", :name "search_phrase", :optional false, :sort false, :stem false, :stem_dictionary "", :store true, :type "string"}
    ;; {:facet false, :index true, :infix false, :locale "", :name "sort_order", :optional false, :sort true, :stem false, :stem_dictionary "", :store true, :type "int32"}
    {:embed {:from ["search_phrase"], :model_config {:model_name "ts/all-MiniLM-L12-v2"}}
     :facet false, :hnsw_params {:M 16, :ef_construction 200}, :index true, :infix false, :locale "", :name "phrase_vec", :num_dim 384, :optional true, :sort false, :stem false, :stem_dictionary "", :store true, :type "float[]", :vec_dist "cosine"}
    ;; {:facet true, :index true, :infix false, :locale "", :name "language", :optional true, :sort false, :stem false, :stem_dictionary "", :store true, :type "string"}
    ;; {:facet false, :index true, :infix false, :locale "", :name "item_priority", :optional false, :sort true, :stem false, :stem_dictionary "", :store true, :type "int64"}
    ;; {:facet false, :index true, :infix false, :locale "", :name "updated_at", :optional false, :sort true, :stem false, :stem_dictionary "", :store true, :type "int64"}
    ;; {:facet false, :index true, :infix false, :locale "", :name "checksum", :optional false, :sort false, :stem false, :stem_dictionary "", :store true, :type "string"}
    ;; {:facet true, :index true, :infix false, :locale "", :name "prompt", :optional true, :sort true, :stem false, :stem_dictionary "", :store true, :type "string"}
    ]
   :name phrases-collection-name
   ;;  :symbols_to_index []
   ;;  :token_separators ["_" "-" "/"]
   })

(defn create-phrases-coll [store kview]
  (let [[docs-coll chunks-coll phrases-coll :as ids] (coll-ids kview)]
    (try
      (t/log! ["Creating collection" phrases-coll])
      (ts/create-collection! ts-admin
                             (kudos-phrases-typesense-schema ids))
      (catch clojure.lang.ExceptionInfo e
        (if (clojure.core/= (:type (ex-data e)) :typesense.client/conflict)
          (do
            (t/log! ["Collection already exists, moving on --" phrases-coll])
            :already-exists)
          (throw e))))))

(defn create-stores [kview]
  (let [[documents-coll chunks-coll :as coll-ids] (coll-ids kview)]
    (doseq [store (:stores kview)]
      (t/event! :document-loading/creating-stores
                {:data {:coll-ids coll-ids
                        :store-type (:store/type kview)}})
      (case (:store/type store)
        :dev/duratom (swap! !duratom-store assoc documents-coll {})
        :typesense (do
                     (create-docs-coll store documents-coll)
                     ;; #377. Placed HERE rather than at boot because this is the
                     ;; moment the schema is about to matter: the collection is
                     ;; named, reachable, and about to be written to. At boot
                     ;; there is no single collection to check, and it would emit
                     ;; :unreachable for every dataset whose Typesense is not
                     ;; configured — normal in dev, and a warning everyone
                     ;; ignores is worse than none because it occupies the slot.
                     ;;
                     ;; WHAT A READER HERE MIGHT OVER-READ, and it differs from
                     ;; the boot-time reader's version:
                     ;;
                     ;; 1. `create-docs-coll` ran one line up, which does NOT
                     ;;    mean the collection matches the file. On conflict it
                     ;;    returns :already-exists and alters nothing. An
                     ;;    existing collection keeps whatever schema it was born
                     ;;    with — which is the entire reason this check is here
                     ;;    and not assumed away.
                     ;; 2. Reachable a moment ago is not reachable now. The
                     ;;    check can still come back :unreachable, and that is
                     ;;    UNKNOWN rather than clean. Do not collapse the three
                     ;;    states to a boolean on the grounds that the
                     ;;    collection is reachable by construction here; it is
                     ;;    reachable by assumption, which is a different thing.
                     ;; 3. This compares against `docs_schema.edn`, which only
                     ;;    `create-docs-coll` reads. It says nothing about the
                     ;;    website, folder or episerver collections — those build
                     ;;    their schemas in code and cannot drift from this file.
                     ;;
                     ;; Reports, never throws: a materialization must not fail
                     ;; over the shape of a collection it did not create.
                     (schema-drift/report! (ts-utils/make-ts-settings
                                             {:tenant (:tenant kview)})
                                           documents-coll)
                     (create-chunks-coll store kview)
                     (create-phrases-coll store kview))))))

(defonce !buffer-before-store-documents-size (atom 0))

(defn reset-failed-documents! []
  (reset! !failed-documents []))

(defn retry-failed-documents! [kview]
  (let [failed-ids @!failed-documents]
    (when (seq failed-ids)
      (t/event! :document-loading/retrying-failed-documents {:data {:doc-ids failed-ids}})
      (reset! !failed-documents [])
      (m/? (m/reduce
            (fn
              ([] nil)
              ([_ _] nil))
            (let [documents-by-ids (partial kudos/documents-by-ids (kudos/profile kview))]
              (mk-store-documents-f
               kview
               (mk-prepare-documents-f
                kview
                (documents-by-ids failed-ids)))))))))

(defn concat-flows [f1 f2]
  (m/ap (m/amb (m/?> f1) (m/?> f2))))

(defn mk-materialize-t [kview]
  (let [kudos-profile (kudos/profile kview)
        documents-by-ids (partial kudos/documents-by-ids kudos-profile)
        documents (partial kudos/documents kudos-profile)]
    (m/sp (t/event! :document-loading/materializing-kview
                    {:data {:kview kview
                            :colls (coll-ids kview)}})
          ;; Chunks coll name will need to be hash of both documents and chunks
          ;; attributes as both impact the chunk generation, e.g:
          ;; (str coll-prefix "chunks_"
          ;;      (sha256-short-hash (merge (extract-ns-from-map kview "documents")
          ;;                                (extract-ns-from-map kview "chunks")))) 

          (m/? (m/via m/blk (create-stores kview)))
          (m/?
           (m/reduce
            rfs/last
            (->> (let [id-docs (documents-by-ids (:documents/first-import-ids kview))
                       filtered-docs (m/buffer 1337 (mk-filter-documents-f kview (documents kview)))
                       docs (if (seq (:documents/types kview))
                              (concat-flows id-docs filtered-docs)
                              id-docs)]
                   (mk-store-documents-f kview (m/buffer 10000 (mk-prepare-documents-f kview docs)))))))
          (t/event! :document-loading/done))))

(comment
  (def prepared-docs (m/? (m/reduce conj (mk-prepare-documents-f kview (m/eduction (take 7)  (kudos/documents (kudos/profile kview) kview))))))

  (defn analyze-doc [doc]
    (sort-by first
             (map (juxt (=> :content_markdown str/split-lines count) :content_markdown)
                  (:chunks doc))))

  (mapv analyze-doc prepared-docs)

  (defonce with-concat (first (m/? (m/reduce conj (mk-prepare-documents-f kview (m/eduction (take 1)  (kudos/documents (kudos/profile kview) kview)))))))
  (defonce without-concat (first (m/? (m/reduce conj (mk-prepare-documents-f kview (m/eduction (take 1)  (kudos/documents (kudos/profile kview) kview)))))))

  ;; Similar total length (good thing)
  (count (->> with-concat :chunks (mapv :content_markdown) str/join)) => 89666
  (count (->> without-concat :chunks (mapv :content_markdown) str/join)) => 89505

  ;; Diffing the :content_markdown shows that they contain ~ the same
  ;; text. with-concat contains an extra prefix header which is
  ;; weird. They are similar enough though. It's not a problem

  (->> with-concat :chunks (map #(dissoc % :content_markdown)) )
  (->> without-concat :chunks (map #(dissoc % :content_markdown)) )

  (->> with-concat :chunks (map (juxt :metadata :content_markdown)) )
  (->> without-concat :chunks (map (juxt :metadata :content_markdown)) )

  )


;;;       -------The Line of Wishful Thought-------       ;;;

(def kview
  {:parallelism/documents 17
   :parallelism/store 1 ;; Typesense is really flaky, let's be super
                        ;; careful not to overload it
   :fault-tolerance/max-document-failures 500
   :skip-already-imported true

   :documents/hash-changer 2
   :kudos/starting-page 1 #_58 #_80
   :kudos/use-preprod? true
   :documents/offset 0
   :documents/limit 20000
   :documents/types  #_#{} #_#{"Årsrapport"} #{"Tildelingsbrev" "Evaluering" "Årsrapport" "Statusrapport"
                                               "Proposisjon til Stortinget" "Strategi/plan" "Melding til Stortinget"
                                               "Instruks"}
   :documents/first-import-ids [19549 368448]
   :documents/transducer '(comp
                           (filter #(str/starts-with? (:publish_date %) "202")))

   ;; #_(foreach #(def fjdkasfjksal %))
   ;;  :documents/limit 3

   :chunks/hash-changer 2
   :chunks/strategy :header-based
   :chunks/minimum-length 333
   ;; gpt4o-mini has max token length of 128000. A token-to-character
   ;; ratio is 3.5-4 on average.
   ;;
   ;; Benjamin says we "tar høyde for" long tokens in the retrieval
   ;; and so it's probably not a blocker to import really long chunks,
   ;; although ideally they would be shorted.
   ;;
   ;; He also made the excellent point that having these anomalies
   ;; imported makes debugging and improving the datagrunnlag after
   ;; the fact easier.
   :chunks/maximum-length (* 2 128000)
   :chunks/embedding-model :Snowflake/snowflake-arctic-embed-l-v2.0

   :search-phrases/hash-changer 1
   :search-phrases/model "gpt-4o"
   :search-phrases/fallback-model :dphn/Dolphin-Mistral-24B-Venice-Edition
   :search-phrases/prompt
   "Analyze the following chunk and generate a list of keyword search phrases 
that have high BM25 information retrieval precision, using the same language as the document. 
If the text is not comprehensible, just return an empty list.
Ouput the search phrases comma separated on the last line.
Make sure that the last line only contains the search phrases and nothing else.
                           
<chunk>
REPLACE_ME
</chunk>"
   ;; Legacy sample config: keep this self-contained so namespace load does not
   ;; depend on any specific tenant surviving in the live config DB.
   :store/coll-prefix "digdir_rag_"
   
   :stores #{{:store/type :typesense}}
   #_(str "TEST-" (str/trim (:out (clojure.java.shell/sh "whoami"))) "-DELETE_ME-")})

(defn -main [& args]
  1
  #_(def cancel-it (run-task-async (mk-materialize-t kview)))
  (m/? (mk-materialize-t kview))
  nil)
#_(cancel-it)

(defn mk-import-single-document-t [kview doc-id]
  "Import a single Kudos document by ID"
  (let [documents-by-ids (partial kudos/documents-by-ids (kudos/profile kview))]
    (m/sp
      (t/event! :document-loading/importing-single-document {:data {:doc-id doc-id}})
      (m/? (m/via m/blk (create-stores kview)))
      (m/?
       (m/reduce
        rfs/last
        (mk-store-documents-f
         kview
         (mk-prepare-documents-f
          kview
          (documents-by-ids [doc-id]))))))))

(defn -main-single [{:keys [doc-id]}]
  "Entry point for importing a single document by ID"
  (let [doc-id-int (cond
                     (integer? doc-id) doc-id
                     (string? doc-id) (Integer/parseInt doc-id)
                     :else (throw (ex-info "doc-id must be provided as an integer or string" {:doc-id doc-id})))]
    (t/event! :document-loading/starting-single-document-import {:data {:doc-id doc-id-int}})
    (def cancel-single-import (run-task-async (mk-import-single-document-t kview doc-id-int)))
    nil))

(comment
  (def res1 (m/? (m/reduce conj (kudos/documents-by-ids kudos/prod [427804 419959 422033]))))
  (def res2 (m/? (m/reduce conj (kudos/documents-by-ids kudos/preprod [425391 417293 419466]))))
  (ddiff/pretty-print (ddiff/diff res1 res2))
  (ddiff/pretty-print (ddiff/diff (first res1) (first res2)))

  (prn :foo)

  (def res1b (m/? (m/reduce conj (mk-prepare-documents-f kview (kudos/documents-by-ids kudos/prod [427804 #_#_419959 422033])))))
  (def res2b (m/? (m/reduce conj (mk-prepare-documents-f kview (kudos/documents-by-ids kudos/preprod [425391 #_#_417293 419466])))))
  (ddiff/pretty-print (ddiff/diff res1b res2b)) ;; lgtm
  (ddiff/pretty-print (ddiff/diff (first res1b) (first res2b))) ;; lvgtm

  )
