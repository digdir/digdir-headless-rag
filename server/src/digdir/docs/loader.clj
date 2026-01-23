(ns digdir.docs.loader
  {:clj-kondo/ignore true}
  (:require [digdir.rag.chunking :as document-chunking]
            [digdir.rag.typesense :as ts-utils]
            [clojure.string :as str]
            [digdir.llm.kudos :as kudos]
            [digdir.llm.kudos-preprod :as kudos-preprod]
            [typesense.client :as ts]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [net.cgrand.xforms.io :as xfio]
            [net.cgrand.xforms.rfs :as rfs]
            [missionary.core :as m]
            [clojure.java.io :as jio]
            [digdir.llm.marker :as marker]
            [duratom.core :refer [duratom]]
            [clojure.java.shell :refer [sh]]
            [wkok.openai-clojure.api :as openai]
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
            [digdir.config.accessor :as cfg]))

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
                                                                 ;; TODO: teach electric to transfer time and error values otw
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

                ;; Fallback: use publish_date year if no concerned year info
                :else
                (if-let [year (extract-year-from-date (:publish_date doc))]
                  [year]
                  []))))))

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

(defn sha256-short-hash [v]
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
  [filename]
  (try
    (marker/->md filename)
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
  (mapv (fn [c] {:chunk_id (sha256-short-hash (str/join c))
                 :content_markdown (str/join c)})
        (partition-all width (- width overlap) text)))

(defn header-based-chunks [kview text]
  #_(t/log! ["Header-based chunking"])
  (let [doc {:page-content text}]
    (->> (document-chunking/split-into-chunks-by-headers kview [doc])
         (mapv (fn [{:keys [page-content metadata]}]
                 {:chunk_id (sha256-short-hash page-content)
                  :content_markdown page-content
                  :metadata (prn-str metadata)})))))

(defn chunk-doc [{:as kview
                  :chunks/keys [strategy minimum-length maximum-length]}]
  (fn [doc]
    (t/event! :document-loading/chunking-document {:data {:id (:id doc)
                                                          :strategy strategy}})
    (def jkdaljdkal doc)
    (let [all-chunks (mapcat
                      (fn [file]
                        (let [md (pdf->md (:path file))]
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
      (assoc doc :chunks (vec (map-indexed (fn [index chunk]
                                             (assoc chunk :doc_num (:doc_num doc) :chunk_index index))
                                           filtered-chunks))))))

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

(defn prepare-doc [doc]
  (let [all-orgs (concat (:owners doc) (:recipients doc) (:publishers doc))
        display-names (vec (distinct (extract-display-names all-orgs)))]

    (select-keys (assoc doc :orgs_long display-names)
                 [:id :doc_num :title :authors :orgs_long
                  :concerned_year :files :publish_date :type :language
                  :concerned_years])))

(defn store-doc [store kview]
  (let [[documents-coll chunks-coll phrases-coll] (coll-ids kview)]
    (case (:store/type store)
      :typesense (fn [doc]

                   ;; TODO: get back to this after rendering real
                   ;; data.... preparing the list is good too fwiw.
                   (if (document-inserted? kview documents-coll doc) ; document doesn't exist
                     (do

;; Megahack: I was getting some weird c++
                       ;; errors... memory corruption and so forth.
                       ;;
                       ;; Caused by data races or something, I'll try
                       ;; slowing it down.
                       (Thread/sleep 1000)

                       (t/event! :document-loading/document-already-in-typesense-skipping-upsert)
                       ;; It's likely that we'll have some documents
                       ;; which are inserted in documents-coll but not
                       ;; in chunks-coll and phrases-coll.
                       ;;
                       ;; Therefore this code is slightly buggy, but
                       ;; right now performance is more important
                       ;;
                       ;; I'm 100% that we can have a query that looks
                       ;; up in phrases-coll, but i don't have the
                       ;; time right now
                       (t/event! :document-loading/TODO:-handle-partially-inserted-documents-correctly))
                     (do

                       (def last-inserted-doc doc)
                       (t/event! :document-loading/upserting-to-documents-typesense-collection)
                       (ts/upsert-document! ts-admin documents-coll (prepare-doc doc))
                       (t/event! :document-loading/upserting-to-chunks-typesense-collection
                                 {:data {:chunk-count (count (:chunks doc))}})
                       (def doc doc)
                       (def chunks-coll chunks-coll)
                       (let [chunk-response (ts/upsert-documents! ts-admin chunks-coll
                                                                  (mapv #(dissoc % :search-phrases :embedding)
                                                                        (:chunks doc)))]
                         #_(t/log! ["Chunk upsert response: " chunk-response]))
                       (t/event! :document-loading/upserting-to-phrases-typesense-collection)
                       (def phrases (mapcat #(for [phrase (:search-phrases %)]
                                               {:search_phrase phrase
                                                :chunk_id (:chunk_id %)
                                                :doc_num (:doc_num doc)})
                                            (:chunks doc)))
                       (ts/upsert-documents! ts-admin phrases-coll phrases)

                       (say "Stored")))

                   (t/event! :document-loading/document-upserted))

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

(defn mk-require-doc-files-t [kview doc]
  (m/sp
   (let [paths (m/? (apply m/join vector (map (=> :url mk-require-url-file) (:files doc))))
         files (mapv #(assoc %1 :path %2) (:files doc) paths)]
     (assoc doc :files files))))

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
(def openai-implementations
  {:lm-studio {:api-endpoint "http://localhost:1234/v1"
               :request {:timeout 300000}}
   #_#_:runpod {:api-endpoint "https://fboqsdcxlh2yxt-8000.proxy.runpod.net/v1"
                :request {:timeout 30000}}
   :azure-openai {:api-key (cfg/get :services :azure-openai :api-key)
                  :api-endpoint (cfg/get :services :azure-openai :api-endpoint)
                  :impl :azure
                  :request {:timeout 30000}
                  ;;  :trace (fn [request response]
                  ;;           #_(println "Request:" request)
                  ;;           (println "Response:" response))
                  }
   :openrouter {:api-key (cfg/get :services :openrouter :api-key)
                :api-endpoint "https://openrouter.ai/api/v1"
                :request {:timeout 30000}}})
;; azure-openai

(defn create-chat-completion [conversation]
  ;; Idea is to dispatch to the right provider based on the model name
  (if (#{:google/gemma-3-27b-it :google/gemma-3-12b-it :dphn/Dolphin-Mistral-24B-Venice-Edition} (:model conversation))
    (openai/create-chat-completion conversation
                                   (openai-implementations :openrouter))
    (openai/create-chat-completion
     (assoc conversation
            :model (cfg/get :services :azure-openai :deployment-name))
     (openai-implementations :azure-openai))))

(defn mk-distill-search-phrases-t [{:search-phrases/keys [model fallback-model prompt] :as kview} chunk]
  (m/via m/blk
         (let [cache-key (str (:chunk_id chunk) "-" (sha256-short-hash model) "-" (sha256-short-hash prompt))
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
               (let [generate-with-model (fn [model]
                                           (->
                                            (let [convo {:model model
                                                         :messages [{:role "user"
                                                                     :content (str/replace prompt "REPLACE_ME" (:content_markdown chunk))}]}]
                                              #_(t/log! ["Generating search phrases with model" model])
                                              (create-chat-completion convo))
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



(defn mk-prepare-document-t [kview doc]
  (m/sp
   #_(m/? (m/sleep 10000))
   (as-> doc doc
     (fill-in-doc-fields doc)

     (m/? (mk-require-doc-files-t kview doc))
     (m/? (mk-chunk-doc-t kview doc))
     (m/? (mk-distill-doc-search-phrases-t kview doc))
     (do (say "Prepared")
         (t/event! :document-loading/document-prepared)
         doc))))

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
            (let [documents-by-ids (if (:kudos/use-preprod? kview)
                                     kudos-preprod/documents-by-ids
                                     kudos/documents-by-ids)]
              (mk-store-documents-f
               kview
               (mk-prepare-documents-f
                kview
                (documents-by-ids failed-ids)))))))))

(defn concat-flows [f1 f2]
  (m/ap (m/amb (m/?> f1) (m/?> f2))))

(defn mk-materialize-t [kview]
  (let [documents-by-ids (if (:kudos/use-preprod? kview)
                           kudos-preprod/documents-by-ids
                           kudos/documents-by-ids)
        documents (if (:kudos/use-preprod? kview)
                    kudos-preprod/documents
                    kudos/documents)]
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
  (def prepared-docs (m/? (m/reduce conj (mk-prepare-documents-f kview (m/eduction (take 7)  (kudos/documents kview))))))

  (defn analyze-doc [doc]
    (sort-by first
             (map (juxt (=> :content_markdown str/split-lines count) :content_markdown)
                  (:chunks doc))))

  (mapv analyze-doc prepared-docs)

  (defonce with-concat (first (m/? (m/reduce conj (mk-prepare-documents-f kview (m/eduction (take 1)  (kudos/documents kview)))))))
  (defonce without-concat (first (m/? (m/reduce conj (mk-prepare-documents-f kview (m/eduction (take 1)  (kudos/documents kview)))))))

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
   :store/coll-prefix (cfg/get :services :typesense :collection-prefix)
   
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
  (let [documents-by-ids (if (:kudos/use-preprod? kview)
                           kudos-preprod/documents-by-ids
                           kudos/documents-by-ids)]
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
  (def res1 (m/? (m/reduce conj (kudos/documents-by-ids [427804 419959 422033]))))
  (def res2 (m/? (m/reduce conj (kudos-preprod/documents-by-ids [425391 417293 419466]))))
  (ddiff/pretty-print (ddiff/diff res1 res2))
  (ddiff/pretty-print (ddiff/diff (first res1) (first res2)))

  (prn :foo)

  (def res1b (m/? (m/reduce conj (mk-prepare-documents-f kview (kudos/documents-by-ids [427804 #_#_419959 422033])))))
  (def res2b (m/? (m/reduce conj (mk-prepare-documents-f kview (kudos-preprod/documents-by-ids [425391 #_#_417293 419466])))))
  (ddiff/pretty-print (ddiff/diff res1b res2b)) ;; lgtm
  (ddiff/pretty-print (ddiff/diff (first res1b) (first res2b))) ;; lvgtm

  )

