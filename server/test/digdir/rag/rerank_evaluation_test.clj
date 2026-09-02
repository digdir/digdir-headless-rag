(ns digdir.rag.rerank-evaluation-test
  "Integration tests evaluating ColBERT reranker score distributions.

   Requires a running config DB, Typesense, and ColBERT service.
   Uses the kudos dataset on public-sector-knowledge/dev environment.

   Run with: bb rerank-eval"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [digdir.config.db :as config-db]
            [digdir.config.core :as config-core]
            [digdir.rag.core :as rag]
            [digdir.rag.live-context :as live-ctx]
            [digdir.rag.typesense :as ts-utils]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [typesense.client :as ts-client]))

;; =============================================================================
;; Configuration & Fixture
;; =============================================================================

(def target-dataset-ref live-ctx/default-dataset-ref)
(def target-chunk-id "6a80d6499075")
(def target-query "Hvor mange årsverk hadde Digdir i 2022?")

(def ^:private !test-config (atom nil))

(defn- rerank-evaluation-enabled?
  []
  (= "true" (some-> (System/getenv "RUN_RERANK_EVALUATION_INTEGRATION") str/lower-case)))

(defn- services-reachable?
  "Check if CONFIG_MASTER_KEY is set and config DB is reachable."
  []
  (and (config-core/get-master-key)
       (try
         (let [conn (config-db/get-conn)]
           (some? conn))
         (catch Exception _ false))))

(defn- resolve-test-config!
  "Resolve pipeline config, collection names, and ColBERT settings for tests."
  []
  (let [{:keys [dataset-config
                collection-names
                docs-collection
                chunks-collection
                phrases-collection
                colbert-url
                colbert-api-key
                ts-opts]} (live-ctx/resolve-live-dataset-context! target-dataset-ref)]
    {:pipeline-config dataset-config
     :collection-names collection-names
     :docs-collection docs-collection
     :chunks-collection chunks-collection
     :phrases-collection phrases-collection
     :colbert-url colbert-url
     :colbert-api-key colbert-api-key
     :ts-opts ts-opts}))

(defn configuration-check-fixture [f]
  (if (rerank-evaluation-enabled?)
    (if (services-reachable?)
      (do
        (println "\n=== ColBERT Reranker Evaluation Tests ===")
        (println (str "Target dataset-ref: " target-dataset-ref))
        (let [config (resolve-test-config!)]
          (println (str "Collections: " (:collection-names config)))
          (println (str "ColBERT URL: " (:colbert-url config)))
          (if (live-ctx/typesense-reachable? (:ts-opts config))
            (do
              (println "Typesense: reachable")
              (reset! !test-config config)
              (f))
            (println "Skipping: Typesense not reachable (run `bb port-forward` in another terminal)"))))
      (println "Skipping rerank evaluation tests: CONFIG_MASTER_KEY not set or config DB not reachable"))
    (println "Skipping rerank evaluation tests: set RUN_RERANK_EVALUATION_INTEGRATION=true to enable live score analysis")))

(use-fixtures :once configuration-check-fixture)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn colbert-rerank-with-scores
  "Call ColBERT and return the raw response with scores preserved."
  [colbert-url colbert-api-key query documents k]
  (let [body (json/generate-string
              {:user_input (subs query 0 (min (count query) 1000))
               :k k
               :documents documents})
        response (http/post colbert-url
                            {:body body
                             :content-type :json
                             :headers {"X-API-Key" colbert-api-key}})]
    (json/parse-string (:body response) true)))

(defn retrieve-chunk-by-id
  "Retrieve a single chunk from Typesense by chunk_id."
  [{:keys [chunks-collection docs-collection ts-opts]}]
  (fn [chunk-id]
    (let [response (ts-client/multi-search
                    (ts-utils/make-ts-settings ts-opts)
                    {:searches [{:collection chunks-collection
                                 :q chunk-id
                                 :include_fields (str "id,chunk_id,doc_num,content_markdown,metadata,$"
                                                      docs-collection "(url,title)")
                                 :filter_by (str "chunk_id:=`" chunk-id "`")
                                 :page 1
                                 :per_page 1}]}
                    {:query_by "chunk_id"})]
      (first (mapcat :hits (get response :results))))))

(defn format-document-for-rerank
  "Format a chunk document for ColBERT reranking, matching core.cljc logic."
  [chunk docs-collection-name max-length]
  (let [title (get-in chunk [:document (keyword docs-collection-name) :title])
        metadata (get-in chunk [:document :metadata])
        content (get-in chunk [:document :content_markdown])
        combined-text (str (when title (str "Title: " title "\n\n"))
                           (when metadata (rag/format-metadata-headers metadata))
                           content)
        truncated (if (> (count combined-text) max-length)
                    (subs combined-text 0 max-length)
                    combined-text)]
    truncated))

(defn print-score-table
  "Print a formatted table of rerank results with scores."
  [results retrieved-chunks docs-collection-name]
  (println "\nRank | Score    | Chunk ID     | Document Title                                        | Relevant?")
  (println "-----|----------|--------------|-------------------------------------------------------|----------")
  (doseq [[idx entry] (map-indexed vector results)]
    (let [;; entry may have :index, :score, :relevance_score or other fields
          chunk-idx (:index entry)
          chunk (when (and (number? chunk-idx)
                           (>= chunk-idx 0)
                           (< chunk-idx (count retrieved-chunks)))
                  (nth retrieved-chunks chunk-idx))
          chunk-id (or (:chunk_id chunk) "???")
          title (when chunk
                  (get-in chunk [(keyword docs-collection-name) :title]))
          short-title (if title
                        (if (> (count title) 55)
                          (str (subs title 0 52) "...")
                          title)
                        "N/A")
          short-id (if (> (count chunk-id) 12) (subs chunk-id 0 12) chunk-id)
          score (or (:score entry) (:relevance_score entry) 0)
          relevant? (when chunk-id
                      (str/starts-with? chunk-id target-chunk-id))]
      (println (format "%4d | %7.3f | %-12s | %-55s | %s"
                       (inc idx) (double score) short-id short-title
                       (if relevant? "YES" ""))))))

(defn find-target-chunk-position
  "Find the position and score of the target chunk in rerank results."
  [results retrieved-chunks]
  (some (fn [[idx entry]]
          (let [chunk-idx (:index entry)
                chunk (when (and (number? chunk-idx)
                                 (>= chunk-idx 0)
                                 (< chunk-idx (count retrieved-chunks)))
                        (nth retrieved-chunks chunk-idx))]
            (when (and chunk (str/starts-with? (or (:chunk_id chunk) "") target-chunk-id))
              {:position (inc idx)
               :score (or (:score entry) (:relevance_score entry) 0)
               :entry entry
               :chunk chunk})))
        (map-indexed vector results)))

;; =============================================================================
;; Test 1: ColBERT Response Format Discovery
;; =============================================================================

(deftest test-colbert-response-format-discovery
  (testing "ColBERT response format - check if scores are returned"
    (let [{:keys [colbert-url colbert-api-key chunks-collection
                  docs-collection ts-opts]} @!test-config
          ;; Retrieve a few known chunks including the target
          fetch-chunk (retrieve-chunk-by-id {:chunks-collection chunks-collection
                                             :docs-collection docs-collection
                                             :ts-opts ts-opts})
          target-hit (fetch-chunk target-chunk-id)
          _ (is (some? target-hit) (str "Target chunk " target-chunk-id " must exist in Typesense"))

          ;; Build a small document set for reranking
          documents [(format-document-for-rerank target-hit docs-collection 2000)]

          ;; Call ColBERT with minimal input
          raw-response (colbert-rerank-with-scores
                        colbert-url colbert-api-key
                        "Hvor mange årsverk hadde Digdir i 2022?"
                        documents 1)]

      (println "\n=== TEST 1: ColBERT Response Format Discovery ===")
      (println "\nRaw ColBERT response:")
      (println (json/generate-string raw-response {:pretty true}))

      (println "\nResponse type:" (type raw-response))
      (println "Is sequential?" (sequential? raw-response))

      (when (sequential? raw-response)
        (println "Number of entries:" (count raw-response))
        (doseq [[idx entry] (map-indexed vector raw-response)]
          (println (str "\nEntry " idx ":"))
          (println "  Keys:" (keys entry))
          (println "  Full entry:" entry)
          (println "  Has :index?" (contains? entry :index))
          (println "  Has :score?" (contains? entry :score))
          (println "  Has :relevance_score?" (contains? entry :relevance_score))
          ;; Check all keys for anything score-like
          (doseq [[k v] entry]
            (when (and (number? v) (not= k :index))
              (println (str "  SCORE FIELD FOUND: " k " = " v))))))

      (when (map? raw-response)
        (println "Response is a map with keys:" (keys raw-response))
        (doseq [[k v] raw-response]
          (println (str "  " k " -> " (type v) " : "
                        (if (sequential? v)
                          (str "[" (count v) " items]")
                          v)))))

      ;; Basic assertions
      (is (some? raw-response) "ColBERT must return a response")
      (let [entries (if (sequential? raw-response)
                      raw-response
                      (when (map? raw-response)
                        ;; Try common response wrapper keys
                        (or (:results raw-response)
                            (:data raw-response)
                            (:rankings raw-response))))]
        (when (seq entries)
          (let [first-entry (first entries)]
            (is (contains? first-entry :index) "Each entry should have :index")
            (println "\n--- VERDICT ---")
            (if (or (contains? first-entry :score)
                    (contains? first-entry :relevance_score))
              (println "ColBERT DOES return scores! Key:"
                       (cond
                         (contains? first-entry :score) ":score"
                         (contains? first-entry :relevance_score) ":relevance_score"))
              (println "ColBERT does NOT return scores in response - only :index"))))))))

;; =============================================================================
;; Test 2: Reproduce Failed Trace — Full Score Capture
;; =============================================================================

(deftest test-reproduce-failed-trace
  (testing "Reproduce failed trace with full score capture"
    (let [{:keys [colbert-url colbert-api-key docs-collection
                  chunks-collection phrases-collection ts-opts]} @!test-config
          search-queries ["årsverk Digdir 2022"
                          "antall ansatte Digdir 2022"
                          "bemanning Digdir 2022"
                          "ressursbruk Digitaliseringsdirektoratet 2022"
                          "ansatte Digitaliseringsdirektoratet 2022"]

          ;; Execute 3-strategy retrieval
          phrase-results (rag/lookup-search-phrases-similar
                          phrases-collection docs-collection
                          search-queries nil ts-opts)
          metadata-results (rag/search-chunks-by-metadata
                            chunks-collection docs-collection
                            search-queries nil ts-opts)
          content-results (rag/search-chunks-by-content
                           chunks-collection docs-collection
                           search-queries nil ts-opts)

          ;; Merge and retrieve full chunks
          merged (rag/merge-chunk-search-results phrase-results metadata-results content-results)
          retrieved-chunks (rag/retrieve-chunks-by-id docs-collection chunks-collection merged ts-opts)

          ;; Prepare documents for ColBERT
          max-chunk-length 2000
          documents (mapv #(let [title (get-in % [(keyword docs-collection) :title])
                                 metadata (:metadata %)
                                 content (:content_markdown %)
                                 combined (str (when title (str "Title: " title "\n\n"))
                                               (when metadata (rag/format-metadata-headers metadata))
                                               content)]
                             (if (> (count combined) max-chunk-length)
                               (subs combined 0 max-chunk-length)
                               combined))
                          retrieved-chunks)

          ;; Call ColBERT for full reranking
          raw-response (colbert-rerank-with-scores
                        colbert-url colbert-api-key
                        target-query documents (count documents))]

      (println "\n=== TEST 2: Reproduce Failed Trace — Full Score Capture ===")
      (println (str "Query: \"" target-query "\""))
      (println (str "Total chunks retrieved: " (count retrieved-chunks)))
      (println (str "Search strategy results — phrase: " (count phrase-results)
                    ", metadata: " (count metadata-results)
                    ", content: " (count content-results)))

      ;; Check if target chunk was retrieved at all
      (let [target-in-retrieved (some #(str/starts-with? (or (:chunk_id %) "") target-chunk-id)
                                      retrieved-chunks)]
        (println (str "\nTarget chunk " target-chunk-id " retrieved: " (boolean target-in-retrieved)))
        (when-not target-in-retrieved
          (println "WARNING: Target chunk was NOT in the retrieved set — cannot evaluate reranking")))

      ;; Display full response structure for first few entries
      (println "\nRaw response sample (first 3 entries):")
      (doseq [entry (take 3 (if (sequential? raw-response) raw-response []))]
        (println "  " entry))

      (let [results (if (sequential? raw-response)
                      raw-response
                      (or (:results raw-response) (:data raw-response) []))]

        ;; Print score table
        (println "\n=== RERANK SCORE ANALYSIS ===")
        (print-score-table results retrieved-chunks docs-collection)

        ;; Find target chunk position
        (let [target-info (find-target-chunk-position results retrieved-chunks)]
          (if target-info
            (do
              (println (str "\nTarget chunk " target-chunk-id " ranked #" (:position target-info)
                            " with score " (:score target-info)))
              (when (pos? (:score target-info))
                (let [top-score (or (:score (first results))
                                    (:relevance_score (first results)) 0)]
                  (println (str "Score gap to #1: " (- (:score target-info) (double top-score))
                                " (" (format "%.1f%%" (* 100.0 (/ (- (double top-score) (:score target-info))
                                                                    (max 0.001 (double top-score)))))
                                " lower)")))))
            (println (str "\nTarget chunk " target-chunk-id " NOT FOUND in rerank results")))

          ;; Document title distribution
          (println "\nDocument title distribution (top 10 vs rest):")
          (let [get-title (fn [entry]
                            (let [idx (:index entry)]
                              (when (and (number? idx) (>= idx 0) (< idx (count retrieved-chunks)))
                                (get-in (nth retrieved-chunks idx)
                                        [(keyword docs-collection) :title]))))
                top-10 (take 10 results)
                rest-results (drop 10 results)]
            (println "  Top 10 titles:")
            (doseq [[idx entry] (map-indexed vector top-10)]
              (println (str "    " (inc idx) ". " (or (get-title entry) "N/A"))))
            (println (str "\n  Remaining " (count rest-results) " chunks — unique titles:"))
            (doseq [title (distinct (keep get-title rest-results))]
              (println (str "    - " title)))))

        (is (pos? (count results)) "ColBERT should return results")))))

;; =============================================================================
;; Test 3: Filtered Search — Score Comparison
;; =============================================================================

(deftest test-filtered-search-score-comparison
  (testing "Filtered search narrowing to Digdir documents"
    (let [{:keys [colbert-url colbert-api-key docs-collection
                  chunks-collection phrases-collection ts-opts]} @!test-config
          search-queries ["årsverk Digdir 2022"
                          "antall ansatte Digdir 2022"
                          "bemanning Digdir 2022"]

          ;; Apply filter to narrow to Digdir documents
          filter-by {:fields [{:type :multiselect
                               :selected-options #{"Digitaliseringsdirektoratet"}
                               :field "orgs_long"}]}

          ;; Execute retrieval with filter
          phrase-results (rag/lookup-search-phrases-similar
                          phrases-collection docs-collection
                          search-queries filter-by ts-opts)
          metadata-results (rag/search-chunks-by-metadata
                            chunks-collection docs-collection
                            search-queries filter-by ts-opts)
          content-results (rag/search-chunks-by-content
                           chunks-collection docs-collection
                           search-queries filter-by ts-opts)

          merged (rag/merge-chunk-search-results phrase-results metadata-results content-results)]

      (println "\n=== TEST 3: Filtered Search — Score Comparison ===")
      (println "Filter: orgs_long = Digitaliseringsdirektoratet")
      (println (str "Merged results: " (count merged)))

      (if (empty? merged)
        (println "WARNING: No chunks found with orgs_long=Digitaliseringsdirektoratet filter.")
        (let [retrieved-chunks (rag/retrieve-chunks-by-id docs-collection chunks-collection merged ts-opts)
              max-chunk-length 2000
              documents (mapv #(let [title (get-in % [(keyword docs-collection) :title])
                                     metadata (:metadata %)
                                     content (:content_markdown %)
                                     combined (str (when title (str "Title: " title "\n\n"))
                                                   (when metadata (rag/format-metadata-headers metadata))
                                                   content)]
                                 (if (> (count combined) max-chunk-length)
                                   (subs combined 0 max-chunk-length)
                                   combined))
                              retrieved-chunks)
              raw-response (when (seq documents)
                             (colbert-rerank-with-scores
                              colbert-url colbert-api-key
                              target-query documents (count documents)))
              results (if (sequential? raw-response)
                        raw-response
                        (or (:results raw-response) (:data raw-response) []))]

          (println (str "Total chunks retrieved: " (count retrieved-chunks)))
          (print-score-table results retrieved-chunks docs-collection)

          (let [target-info (find-target-chunk-position results retrieved-chunks)]
            (if target-info
              (println (str "\nTarget chunk " target-chunk-id " ranked #" (:position target-info)
                            " with score " (:score target-info)))
              (println (str "\nTarget chunk " target-chunk-id " NOT FOUND in filtered results"))))))

      (is true "Filtered search completed"))))

;; =============================================================================
;; Test 4: Threshold Analysis
;; =============================================================================

(deftest test-threshold-analysis
  (testing "Score distribution and threshold analysis"
    (let [{:keys [colbert-url colbert-api-key docs-collection
                  chunks-collection phrases-collection ts-opts]} @!test-config
          search-queries ["årsverk Digdir 2022"
                          "antall ansatte Digdir 2022"
                          "bemanning Digdir 2022"
                          "ressursbruk Digitaliseringsdirektoratet 2022"
                          "ansatte Digitaliseringsdirektoratet 2022"]

          ;; Retrieve chunks (unfiltered)
          phrase-results (rag/lookup-search-phrases-similar
                          phrases-collection docs-collection
                          search-queries nil ts-opts)
          metadata-results (rag/search-chunks-by-metadata
                            chunks-collection docs-collection
                            search-queries nil ts-opts)
          content-results (rag/search-chunks-by-content
                           chunks-collection docs-collection
                           search-queries nil ts-opts)

          merged (rag/merge-chunk-search-results phrase-results metadata-results content-results)
          retrieved-chunks (rag/retrieve-chunks-by-id docs-collection chunks-collection merged ts-opts)

          max-chunk-length 2000
          documents (mapv #(let [title (get-in % [(keyword docs-collection) :title])
                                 metadata (:metadata %)
                                 content (:content_markdown %)
                                 combined (str (when title (str "Title: " title "\n\n"))
                                               (when metadata (rag/format-metadata-headers metadata))
                                               content)]
                             (if (> (count combined) max-chunk-length)
                               (subs combined 0 max-chunk-length)
                               combined))
                          retrieved-chunks)

          raw-response (colbert-rerank-with-scores
                        colbert-url colbert-api-key
                        target-query documents (count documents))

          results (if (sequential? raw-response)
                    raw-response
                    (or (:results raw-response) (:data raw-response) []))]

      (println "\n=== TEST 4: Threshold Analysis ===")
      (println (str "Query: \"" target-query "\""))
      (println (str "Total chunks: " (count retrieved-chunks)))

      ;; Check if scores exist
      (let [has-scores? (and (seq results)
                             (or (:score (first results))
                                 (:relevance_score (first results))))
            get-score (fn [entry] (or (:score entry) (:relevance_score entry) 0))]

        (if-not has-scores?
          (do
            (println "\nNo scores in ColBERT response — threshold analysis not possible")
            (println "ColBERT only returns ordering (indices), not scores")
            (println "\nAlternative analysis: position-based filtering")
            (println "If we take top-K by ColBERT ordering:")
            (let [target-info (find-target-chunk-position results retrieved-chunks)]
              (when target-info
                (doseq [k [5 8 10 15 20]]
                  (let [keeps-target? (<= (:position target-info) k)]
                    (println (format "  Top-%d: %s target (at position #%d)"
                                     k (if keeps-target? "KEEPS" "LOSES") (:position target-info))))))))

          ;; Full score analysis
          (let [scores (map get-score results)
                max-score (apply max scores)
                min-score (apply min scores)
                mean-score (/ (reduce + scores) (count scores))

                ;; Identify relevant chunks (from Digdir annual report)
                relevant-indices (keep-indexed
                                  (fn [idx entry]
                                    (let [chunk-idx (:index entry)
                                          chunk (when (and (number? chunk-idx)
                                                           (>= chunk-idx 0)
                                                           (< chunk-idx (count retrieved-chunks)))
                                                  (nth retrieved-chunks chunk-idx))
                                          title (when chunk
                                                  (get-in chunk [(keyword docs-collection) :title]))]
                                      (when (and title (or (str/includes? title "Digitaliseringsdirektoratet")
                                                           (str/includes? title "Digdir")))
                                        idx)))
                                  results)
                relevant-scores (map #(get-score (nth results %)) relevant-indices)
                irrelevant-scores (map get-score
                                       (keep-indexed
                                        (fn [idx entry]
                                          (when-not (some #{idx} (set relevant-indices))
                                            entry))
                                        results))]

            (println "\n--- Score Distribution ---")
            (println (format "  Max score:  %.3f" (double max-score)))
            (println (format "  Min score:  %.3f" (double min-score)))
            (println (format "  Mean score: %.3f" (double mean-score)))
            (println (format "  Range:      %.3f" (double (- max-score min-score))))

            (when (seq relevant-scores)
              (println (format "\n  Relevant chunk scores (Digdir docs): %s"
                               (str/join ", " (map #(format "%.3f" (double %)) relevant-scores)))
                       )
              (println (format "  Min relevant score: %.3f" (double (apply min relevant-scores))))
              (println (format "  Max relevant score: %.3f" (double (apply max relevant-scores)))))

            (when (seq irrelevant-scores)
              (println (format "\n  Irrelevant chunk scores: min=%.3f max=%.3f"
                               (double (apply min irrelevant-scores))
                               (double (apply max irrelevant-scores)))))

            ;; Histogram
            (println "\n--- Score Histogram ---")
            (let [bucket-size (max 1.0 (/ (- max-score min-score) 10))
                  buckets (group-by #(int (/ (- (get-score %) min-score) bucket-size)) results)]
              (doseq [b (sort (keys buckets))]
                (let [low (+ min-score (* b bucket-size))
                      high (+ low bucket-size)
                      entries (get buckets b)
                      relevant-in-bucket (count (filter (fn [entry]
                                                          (some #{(.indexOf results entry)} (set relevant-indices)))
                                                        entries))]
                  (println (format "  [%6.1f - %6.1f): %3d chunks (%d relevant) %s"
                                   (double low) (double high)
                                   (count entries) relevant-in-bucket
                                   (apply str (repeat (count entries) "#")))))))

            ;; Threshold candidates
            (println "\n--- Threshold Candidates ---")
            (let [target-info (find-target-chunk-position results retrieved-chunks)]
              (doseq [pct [0.9 0.8 0.7 0.6 0.5 0.4]]
                (let [threshold (* pct max-score)
                      kept (filter #(>= (get-score %) threshold) results)
                      keeps-target? (when target-info
                                      (>= (:score target-info) threshold))]
                  (println (format "  Cut at %.1f (%d%% of max): keeps %d chunks, %s target"
                                   (double threshold) (int (* 100 pct))
                                   (count kept)
                                   (if keeps-target? "KEEPS" "LOSES")))))

              ;; Separation gap
              (when (and (seq relevant-scores) (seq irrelevant-scores))
                (let [min-relevant (apply min relevant-scores)
                      max-irrelevant (apply max irrelevant-scores)
                      gap (- min-relevant max-irrelevant)]
                  (println (format "\n--- Separation Gap ---"))
                  (println (format "  Min relevant score:  %.3f" (double min-relevant)))
                  (println (format "  Max irrelevant score: %.3f" (double max-irrelevant)))
                  (if (pos? gap)
                    (println (format "  Gap: +%.3f (CLEAN SEPARATION)" (double gap)))
                    (println (format "  Gap: %.3f (OVERLAP — thresholding will lose relevant chunks or keep noise)"
                                     (double gap)))))))))

        (is true "Threshold analysis completed")))))

;; =============================================================================
;; Test 5: Multi-Query Score Stability
;; =============================================================================

(deftest test-multi-query-score-stability
  (testing "ColBERT score stability across query reformulations"
    (let [{:keys [colbert-url colbert-api-key docs-collection
                  chunks-collection phrases-collection ts-opts]} @!test-config
          ;; Use the same queries as Test 2 to retrieve a shared chunk set
          search-queries ["årsverk Digdir 2022"
                          "antall ansatte Digdir 2022"
                          "bemanning Digdir 2022"
                          "ressursbruk Digitaliseringsdirektoratet 2022"
                          "ansatte Digitaliseringsdirektoratet 2022"]

          phrase-results (rag/lookup-search-phrases-similar
                          phrases-collection docs-collection
                          search-queries nil ts-opts)
          metadata-results (rag/search-chunks-by-metadata
                            chunks-collection docs-collection
                            search-queries nil ts-opts)
          content-results (rag/search-chunks-by-content
                           chunks-collection docs-collection
                           search-queries nil ts-opts)

          merged (rag/merge-chunk-search-results phrase-results metadata-results content-results)
          retrieved-chunks (rag/retrieve-chunks-by-id docs-collection chunks-collection merged ts-opts)

          max-chunk-length 2000
          documents (mapv #(let [title (get-in % [(keyword docs-collection) :title])
                                 metadata (:metadata %)
                                 content (:content_markdown %)
                                 combined (str (when title (str "Title: " title "\n\n"))
                                               (when metadata (rag/format-metadata-headers metadata))
                                               content)]
                             (if (> (count combined) max-chunk-length)
                               (subs combined 0 max-chunk-length)
                               combined))
                          retrieved-chunks)

          ;; Query variants to test stability
          query-variants ["Hvor mange årsverk hadde Digdir i 2022?"
                          "årsverk Digdir 2022"
                          "antall ansatte Digitaliseringsdirektoratet 2022"
                          "bemanning Digdir"
                          "FTEs Digdir 2022"]]

      (println "\n=== TEST 5: Multi-Query Score Stability ===")
      (println (str "Chunk set size: " (count retrieved-chunks)))
      (println (str "Query variants: " (count query-variants)))

      (let [results-by-query
            (mapv (fn [query]
                    (let [raw-response (colbert-rerank-with-scores
                                        colbert-url colbert-api-key
                                        query documents (count documents))
                          results (if (sequential? raw-response)
                                    raw-response
                                    (or (:results raw-response) (:data raw-response) []))
                          target-info (find-target-chunk-position results retrieved-chunks)]
                      {:query query
                       :results results
                       :target-position (:position target-info)
                       :target-score (:score target-info)}))
                  query-variants)]

        (println "\n--- Target Chunk Position Across Queries ---")
        (println "Query                                              | Position | Score")
        (println "---------------------------------------------------|----------|--------")
        (doseq [{:keys [query target-position target-score]} results-by-query]
          (let [short-query (if (> (count query) 50)
                              (str (subs query 0 47) "...")
                              query)]
            (println (format "%-50s | %8s | %s"
                             short-query
                             (or target-position "N/A")
                             (if target-score (format "%.3f" (double target-score)) "N/A")))))

        ;; Compute position variance
        (let [positions (keep :target-position results-by-query)]
          (when (seq positions)
            (let [mean-pos (/ (reduce + positions) (count positions))
                  variance (/ (reduce + (map #(Math/pow (- % mean-pos) 2) positions))
                              (count positions))]
              (println (format "\nPosition statistics: mean=%.1f, std=%.1f, min=%d, max=%d"
                               (double mean-pos) (Math/sqrt variance)
                               (apply min positions) (apply max positions))))))

        ;; Compute score variance if available
        (let [scores (keep :target-score results-by-query)]
          (when (seq scores)
            (let [mean-score (/ (reduce + scores) (count scores))
                  variance (/ (reduce + (map #(Math/pow (- % mean-score) 2) scores))
                              (count scores))]
              (println (format "Score statistics: mean=%.3f, std=%.3f, min=%.3f, max=%.3f"
                               (double mean-score) (Math/sqrt variance)
                               (double (apply min scores)) (double (apply max scores)))))))

        (is true "Multi-query stability analysis completed")))))
