(ns digdir.rag.core
  (:require #?(:clj [clojure.data.json :as json])
            #?(:clj [typesense.client :as ts-client])
            #?(:clj [medley.core :as medley])
            #?(:clj [cheshire.core :as cheshire])
            #?(:clj [nano-id.core :refer [nano-id]])
            ;; [hashp.core :refer [p]]
            #?(:clj [clj-http.client :as http])
            #?(:clj [wkok.openai-clojure.api :as openai])
            #?(:clj [digdir.llm.openai :as llm])
            #?(:clj [litellm.core :as litellm])
            #?(:clj [datahike.api :as d])
            #?(:clj [digdir.data.db :as db])
            #?(:clj [digdir.config.accessor :as cfg])
            #?(:clj [digdir.rag.typesense :as ts-utils])
            #?(:clj [markdown.core :as md2])
            [clojure.string :as str]
            [clojure.edn :as edn]
            [lambdaisland.deep-diff2 :as ddiff]))
(defn T
  "For debugging
  Input → ___ → Output
           |
           |
           ↓
        Console"
  ([x]
   (prn x)
   x)
  ([tag x]
   (prn tag x)
   x))

(defn print-diff [old new]
  (-> (ddiff/diff old new)
      ddiff/minimize
      ddiff/pretty-print ;; Printing directly with
      ;; ddiff/pretty-print does not look
      ;; right
      with-out-str
      print))

(def stage-name "DOCS_QA_RAG")

#?(:clj (defonce !response-states (atom {})))

#?(:clj
   (defn update-response-state!
     "Update the response state for a specific conversation"
     [convo-id state-message]
     (if state-message
       (swap! !response-states assoc convo-id [state-message])
       (swap! !response-states dissoc convo-id))))

;; Queue to store RAG jobs
#?(:clj (defonce !rag-jobs (atom [])))


;; Header parsing and formatting functions
(defn parse-header-level
  "Parse a header level string to integer, returns nil if invalid"
  [level-str]
  (try
    #?(:clj (Integer/parseInt level-str)
       :cljs (let [n (js/parseInt level-str 10)]
               (when-not (js/isNaN n) n)))
    (catch #?(:clj Exception :cljs js/Error) _
      nil)))

(defn format-header
  "Format a single header with the specified level"
  [level content]
  (str (str/join (repeat level "#")) " " content))

(defn extract-header-entries
  "Extract and sort header entries from a metadata map"
  [metadata-map]
  (if (map? metadata-map)
    (->> metadata-map
         (keep (fn [[k v]]
                 (let [k-str (cond
                               (string? k) k
                               (keyword? k) (name k)
                               :else (str k))
                       header-match (re-matches #"Header (\d+)" k-str)]
                   (when header-match
                     (let [level-str (second header-match)]
                       (when-let [level (parse-header-level level-str)]
                         (when (pos? level)
                           [(min 4 (+ 2 level)) v])))))))
         (into []))
    []))

(defn format-metadata-headers
  "Formats metadata as markdown headers. If metadata is a map with 'Header N' keys,
  converts it to markdown headers (e.g., '# Title' for 'Header 1'). Otherwise,
  returns nil."
  [metadata]
  (let [metadata-map (cond
                       ;; If metadata is a string, try to read it as EDN
                       (string? metadata) (try
                                            (let [parsed (edn/read-string metadata)]
                                              ;; Check if we need to parse again (double-encoded)
                                              (if (string? parsed)
                                                (edn/read-string parsed)
                                                parsed))
                                            (catch #?(:clj Exception :cljs js/Error) _
                                              nil))
                       ;; If metadata is already a map
                       (map? metadata) metadata
                       :else nil)]
    (when (map? metadata-map)
      (let [header-entries (extract-header-entries metadata-map)
            sorted-entries (sort-by first header-entries)]
        (when (seq sorted-entries)
          (str/join "\n"
                    (map (fn [[level content]]
                           (format-header level content))
                         sorted-entries)))))))

#?(:clj
   (defn enqueue-rag-job
     "Add a new RAG job to the queue"
     [job-data]
     (swap! !rag-jobs conj job-data)))

#?(:clj
   (defn dequeue-rag-job
     "Remove and return the next RAG job from the queue"
     []
     (when-let [job (first @!rag-jobs)]
       (swap! !rag-jobs subvec 1)
       job)))

#?(:clj
   (defn has-pending-jobs?
     "Check if there are any pending RAG jobs"
     []
     (boolean (seq @!rag-jobs))))

#?(:clj
   (def search-results-tools
     [{:type "function"
       :function
       {:name "searchPhrases"
        :parameters
        {:type "object"
         :properties
         {:searchPhrases
          {:type "array"
           :items {:type "string"}}}}}}]))

#?(:clj
   (defn do-query-relaxation
     [prompt-rag-query-relax messages selected-model]
     (let [formatted-messages
           (map (fn [msg]
                  (str (if (= (:message/role msg) :user) "User: " "Assistant: ")
                       "\"" (:message/text msg) "\""))
                messages)
           message-string (str/join "\n\n" formatted-messages)
           _ (println "Formatted messages:")
           _ (println message-string)

           prompt (-> (or prompt-rag-query-relax "")
                      (str/replace "{messages}" message-string))
           deployment (cfg/get :services :azure-openai :deployment-name)
           query-result
           (litellm/completion :azure-openai deployment
                               {:messages [{:role :user :content prompt}]
                                :tools search-results-tools
                                :tool-choice :required
                                :temperature 0.1}
                               {:api-key (cfg/get :services :azure-openai :api-key)
                                :api-base (cfg/get :services :azure-openai :api-endpoint)
                                :api-version (cfg/get :services :azure-openai :api-version)
                                :deployment deployment})]
       (when query-result
         (let [tool-calls (-> query-result :choices first :message :tool-calls)
               all-search-phrases
               (mapcat (fn [tool-call]
                         (try
                           (let [json (-> tool-call :function :arguments)
                                 decoded (clojure.data.json/read-str json :key-fn keyword)]
                             (:searchPhrases decoded))
                           (catch Exception e
                             (println "Error decoding JSON:" (.getMessage e))
                             nil)))
                       tool-calls)]
           (vec all-search-phrases))))))

;;       #_(doseq [i (range (count (:searchQueries @query-result)))]
;;          (swap! query-result update-in [:searchQueries i] #(-> % (.replace "GitHub" "") .trim)))

#?(:clj

   (defn query-relaxation [prompt-rag-query-relax messages selected-model]
     (let [max-retries 5
           retry-delay 500 ;; ms
           ]
       (loop [attempt 1]
         (let [result (try
                        (do-query-relaxation prompt-rag-query-relax messages selected-model)
                        (catch Exception e
                          (println "Error in do-query-relaxation attempt" attempt ":" (.getMessage e))
                          nil))]
           (if (or result (>= attempt max-retries))
             result
             (do
               (Thread/sleep retry-delay)
               (recur (inc attempt)))))))))

(defn format-filter-value
  "Format a filter value for Typesense. Integers don't need backticks, strings do."
  [value value-type]
  (if (= value-type :integer)
    (str value)
    (str "`" value "`")))

#?(:clj
   (defn filter-map->typesense-filter
     "Converts a filter map to a Typesense filter_by string"
     [{:keys [fields]} docs-collection-name]
     (let [selected-fields (->> fields
                                (map (fn [{:keys [field selected-options value-type]}]
                                       (when (seq selected-options)
                                         (str field ":=["
                                              (str/join ","
                                                        (map #(format-filter-value % value-type) selected-options))
                                              "]"))))
                                (remove nil?))]
       (when (seq selected-fields)
         (str "$" docs-collection-name "("
              (str/join " && " selected-fields)
              ")")))))

(comment

  (filter-map->typesense-filter
   {:fields [{:type :multiselect
              :selected-options #{"DFD"}
              :field "orgs_short"}
             {:type :multiselect
              :selected-options #{"Digdir"}
              :field "owner_short"}]}
   "my_collection"))

#?(:clj
   (defn filter-map->typesense-facet-multi-search
     "Converts a filter map to a Typesense multi-search with disjunctive faceting"
     [{:keys [fields max-options]} docs-collection-name]
     (let [all-field-names (map :field fields)
           fields-with-selections (filter #(not-empty (:selected-options %)) fields)

           ;; Helper function to create filter string for selected options
           create-filter-str (fn [excluded-field]
                               (->> fields-with-selections
                                    (remove #(= (:field %) excluded-field))
                                    (map (fn [{:keys [field selected-options value-type]}]
                                           (str field ":=["
                                                (str/join ","
                                                          (map #(format-filter-value % value-type) selected-options))
                                                "]")))
                                    (str/join " && ")))

           ;; Main search with all filters and facets
           all-facets (into {}
                            (remove (fn [[_ v]] (#{"" nil} v)))
                            (merge
                             {:q "*"
                              :query_by "doc_num"
                              :facet_by (str/join "," all-field-names)
                              :page 1
                              :max_facet_values (or max-options 300)
                              :per_page 6
                              :collection docs-collection-name}))

           ;; Main search with all filters and facets
           main-search (into {}
                             (remove (fn [[_ v]] (#{"" nil} v)))
                             (merge
                              {:q "*"
                               :query_by "doc_num"
                               :facet_by (str/join "," all-field-names)
                               :page 1
                               :max_facet_values (or max-options 300)
                               :per_page 6
                               :collection docs-collection-name}
                              (when (seq fields-with-selections)
                                {:filter_by (create-filter-str nil)})))

           ;; Individual facet searches - only for fields with selections
           facet-searches (map (fn [{:keys [field]}]
                                 (into {}
                                       (remove (fn [[_ v]] (#{"" nil} v)))
                                       (merge
                                        {:q "*"
                                         :query_by "doc_num"
                                         :facet_by field
                                         :page 1
                                         :max_facet_values (or max-options 300)
                                         :collection docs-collection-name}
                                        (when-let [filter-str (create-filter-str field)]
                                          {:filter_by filter-str}))))
                               fields-with-selections)]

       {:searches (vec (concat [all-facets main-search] facet-searches))})))

(defn rcomp [& fns]
  (apply comp (reverse fns)))

#?(:clj
   (defn field->counts [facet_counts]
     (into {}
           (map (fn [{:keys [counts field_name]}]
                  [field_name counts]))
           facet_counts)))

#?(:clj
   (defn options [res]
     (def options-input res)
     (let [all-facet-counts (mapcat :facet_counts res)
           ;; Group by field_name first
           grouped (group-by :field_name all-facet-counts)
           ;; For each field_name, combine counts taking max count for each value
           field->options
           (reduce-kv
            (fn [acc field-name field-entries]
              (let [all-counts (mapcat :counts field-entries)
                    ;; Group counts by value and take max count for each
                    combined-counts (->> all-counts
                                         (group-by :value)
                                         (map (fn [[value entries]]
                                                (if (seq entries)
                                                  {:value value
                                                   :count (if (seq (rest entries))
                                                            (apply max (map :count (rest entries)))
                                                            0)}
                                                  {:value value
                                                   :count 0})))
                                         (sort-by :value)
                                         vec)]
                (assoc acc field-name combined-counts)))
            {}
            grouped)]
       field->options)))

#?(:clj
   (defn facet-result->ui-field [{:as filter-field :keys [selected-options value-type]} options]
     (comment (facet-result->ui-field filter-field options))
     (assoc filter-field :options (mapv (fn [{:keys [count value]}]
                                          ;; Convert integer values to strings for UI consistency
                                          (let [str-value (str value)]
                                            {:count count
                                             :value str-value
                                             :selected? (boolean (selected-options str-value))}))
                                        options))))

#?(:clj
   (defn fetch-facets
     "Fetch facets from Typesense for filtering UI.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([conversation-entity filter-map]
      (fetch-facets conversation-entity filter-map nil))
     ([conversation-entity filter-map opts]
      (println "fetch-facets called with entity:" (:docs-collection conversation-entity) "filter-map:" filter-map)
      (if-let [ts-config (ts-utils/make-ts-settings opts)]
        (let [multi-search (filter-map->typesense-facet-multi-search
                            filter-map
                            (:docs-collection conversation-entity))
              _ (println "fetch-facets multi-search query:" (pr-str multi-search))
              ]
          (try
            (let [response (ts-client/multi-search
                            ts-config multi-search {:query_by "doc_num"})
                  _ (println "fetch-facets response:" (pr-str response))
                  results (:results response)
                  facet-opts (options results)
                  _ (println "fetch-facets options:" (pr-str facet-opts))]
              (assoc filter-map :ui/fields
                     (mapv (fn [filter-field]
                             (facet-result->ui-field
                              filter-field (facet-opts (:field filter-field))))
                           (:fields filter-map))))
            (catch Exception e
              (println "Error in fetch-facets:" (.getMessage e) "Error: " (str e) "queries:" multi-search)
              filter-map)))
        (do
          (println "fetch-facets: ts-config is nil!")
          ;; Return filter-map unchanged if ts-config is not available
          filter-map)))))

#?(:clj
   (defn prepare-conversation [dh-conn convo-id conversation-entity]
     (mapv
      #(cond-> %
         (:message.filter/value %) (update :message.filter/value (partial fetch-facets conversation-entity)))
      (db/fetch-convo-messages-mapped dh-conn convo-id))))

#?(:clj
   (defn lookup-search-phrases-similar
     "Search for similar phrases in Typesense.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([phrases-collection-name docs-collection-name relaxed-queries prompt filter-by]
      (lookup-search-phrases-similar phrases-collection-name docs-collection-name relaxed-queries prompt filter-by nil))
     ([phrases-collection-name docs-collection-name relaxed-queries prompt filter-by opts]
      (println "lookup-search-phrases-similar()" "filter-by:" filter-by)
      (if (or (nil? relaxed-queries) (nil? phrases-collection-name) (nil? prompt))
        (do
          (println "typesenseSearchMultiple() - search terms not provided")
          [])
        (let [typesense-filter (filter-map->typesense-filter filter-by docs-collection-name)
              multi-search-args {:searches (map (fn [query]
                                                  (merge
                                                   {:collection phrases-collection-name
                                                    :q query
                                                    :include_fields "chunk_id,search_phrase"
                                                    :exclude_fields "phrase_vec"
                                                    :limit 20
                                                    :sort_by "_text_match:desc"
                                                    :prioritize_exact_match false
                                                    :drop_tokens_threshold 5}
                                                   (when (not-empty typesense-filter)
                                                     {:filter_by typesense-filter})))
                                                relaxed-queries)}]
          (prn "lookup-search-phrases-similar queries:")
          (prn multi-search-args)
          (let [response (ts-client/multi-search (ts-utils/make-ts-settings opts) multi-search-args {:query_by "search_phrase,phrase_vec"})
                indexed-search-phrase-hits (->> (:results response)
                                                (mapcat :hits)
                                                (map-indexed (fn [idx phrase]
                                                               (assoc phrase :index idx))))
                chunk-id-list (map (fn [phrase]
                                     (let [rank-val (get-in phrase [:hybrid_search_info :rank_fusion_score])]
                                       {:chunk_id (get-in phrase [:document :chunk_id])
                                        :rank (cond
                                                (nil? rank-val) 0
                                                (number? rank-val) rank-val
                                                (string? rank-val) (try (Double/parseDouble rank-val) (catch Exception _ 0))
                                                :else 0)
                                        :index (:index phrase)}))
                                   indexed-search-phrase-hits)]
            #_(when (= 1 1)
                (prn "lookupSearchPhraseSimilar results:")
                (prn response))
            chunk-id-list))))))

#?(:clj
   (defn search-chunks-by-metadata
     "Search chunks by metadata in Typesense.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([chunks-collection-name docs-collection-name relaxed-queries filter-by]
      (search-chunks-by-metadata chunks-collection-name docs-collection-name relaxed-queries filter-by nil))
     ([chunks-collection-name docs-collection-name relaxed-queries filter-by opts]
      (println "search-chunks-by-metadata()" "filter-by:" filter-by)
      (if (or (nil? relaxed-queries) (nil? chunks-collection-name))
        (do
          (println "search-chunks-by-metadata() - search terms not provided")
          [])
        (let [typesense-filter (filter-map->typesense-filter filter-by docs-collection-name)
              multi-search-args {:searches (map (fn [query]
                                                  (merge
                                                   {:collection chunks-collection-name
                                                    :q query
                                                    :query_by "metadata"
                                                    :include_fields "chunk_id,metadata"
                                                    :limit 20
                                                    :sort_by "_text_match:desc"
                                                    :prioritize_exact_match false
                                                    :drop_tokens_threshold 5}
                                                   (when (not-empty typesense-filter)
                                                     {:filter_by typesense-filter})))
                                                relaxed-queries)}]
          (prn "search-chunks-by-metadata queries:")
          (prn multi-search-args)
          (let [response (ts-client/multi-search (ts-utils/make-ts-settings opts) multi-search-args {})
                indexed-metadata-hits (->> (:results response)
                                           (mapcat :hits)
                                           (map-indexed (fn [idx hit]
                                                          (assoc hit
                                                                 :index idx
                                                                 :search-type :metadata))))
                chunk-id-list (map (fn [hit]
                                     (let [rank-val (get-in hit [:text_match_info :score])]
                                       {:chunk_id (get-in hit [:document :chunk_id])
                                        :rank (cond
                                                (nil? rank-val) 0
                                                (number? rank-val) rank-val
                                                (string? rank-val) (try (Double/parseDouble rank-val) (catch Exception _ 0))
                                                :else 0)
                                        :index (:index hit)
                                        :search-type :metadata}))
                                   indexed-metadata-hits)]
            (prn (str "search-chunks-by-metadata found " (count chunk-id-list) " results"))
            chunk-id-list))))))

#?(:clj
   (defn search-chunks-by-content
     "Search chunks by content in Typesense.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([chunks-collection-name docs-collection-name relaxed-queries filter-by]
      (search-chunks-by-content chunks-collection-name docs-collection-name relaxed-queries filter-by nil))
     ([chunks-collection-name docs-collection-name relaxed-queries filter-by opts]
      (println "search-chunks-by-content()" "filter-by:" filter-by)
      (if (or (nil? relaxed-queries) (nil? chunks-collection-name))
        (do
          (println "search-chunks-by-content() - search terms not provided")
          [])
        (let [typesense-filter (filter-map->typesense-filter filter-by docs-collection-name)
              multi-search-args {:searches (map (fn [query]
                                                  (merge
                                                   {:collection chunks-collection-name
                                                    :q query
                                                    :query_by "content_markdown"
                                                    :include_fields "chunk_id,content_markdown"
                                                    :limit 20
                                                    :sort_by "_text_match:desc"
                                                    :prioritize_exact_match false
                                                    :drop_tokens_threshold 5}
                                                   (when (not-empty typesense-filter)
                                                     {:filter_by typesense-filter})))
                                                relaxed-queries)}]
          (prn "search-chunks-by-content queries:")
          (prn multi-search-args)
          (let [response (ts-client/multi-search (ts-utils/make-ts-settings opts) multi-search-args {})
                indexed-content-hits (->> (:results response)
                                          (mapcat :hits)
                                          (map-indexed (fn [idx hit]
                                                         (assoc hit
                                                                :index idx
                                                                :search-type :content))))
                chunk-id-list (map (fn [hit]
                                     (let [rank-val (get-in hit [:text_match_info :score])]
                                       {:chunk_id (get-in hit [:document :chunk_id])
                                        :rank (cond
                                                (nil? rank-val) 0
                                                (number? rank-val) rank-val
                                                (string? rank-val) (try (Double/parseDouble rank-val) (catch Exception _ 0))
                                                :else 0)
                                        :index (:index hit)
                                        :search-type :content}))
                                   indexed-content-hits)]
            (prn (str "search-chunks-by-content found " (count chunk-id-list) " results"))
            chunk-id-list))))))

#?(:clj
   (defn normalize-ranks
     "Normalizes ranks within a list using min-max normalization to 0-1 range.
      This ensures scores from different search types (phrase, metadata, content)
      are comparable when merged."
     [results]
     (if (empty? results)
       results
       (let [ranks (map :rank results)
             min-rank (apply min ranks)
             max-rank (apply max ranks)
             range-val (- max-rank min-rank)]
         (if (zero? range-val)
           ;; All ranks are the same, assign 1.0 to all
           (map #(assoc % :rank 1.0) results)
           ;; Normalize to 0-1 range
           (map #(assoc % :rank (double (/ (- (:rank %) min-rank) range-val))) results))))))

#?(:clj
   (defn merge-chunk-search-results
     "Merges chunk IDs from multiple search results, preserving search-type information
      and combining ranks when chunks appear in multiple searches.
      Normalizes scores within each search type before merging to ensure fair comparison."
     [& search-results]
     ;; First, normalize each search result set independently
     (let [normalized-results (map normalize-ranks search-results)
           all-results (apply concat normalized-results)
           grouped-by-chunk-id (group-by :chunk_id all-results)]
       (->> grouped-by-chunk-id
            (map (fn [[chunk-id hits]]
                   (let [search-types (set (map :search-type hits))
                         ;; Convert ranks to numbers, handling nil and string values
                         numeric-ranks (map #(let [r (:rank %)]
                                               (cond
                                                 (nil? r) 0
                                                 (number? r) r
                                                 (string? r) (try (Double/parseDouble r) (catch Exception _ 0))
                                                 :else 0)) hits)
                         best-rank (if (empty? numeric-ranks) 0 (apply max numeric-ranks))
                         ;; Indices should already be numbers, but let's be safe
                         numeric-indices (map #(or (:index %) 0) hits)
                         best-index (if (empty? numeric-indices) 0 (apply min numeric-indices))]
                     {:chunk_id chunk-id
                      :rank best-rank
                      :index best-index
                      :search-types search-types
                      :hit-count (count hits)})))
            (sort-by (juxt (comp - :hit-count) (comp - :rank) :index))
            vec))))

#?(:clj
   (defn lookup-chunks-by-search-phrases
     [phrases-collection-name docs-collection-name relaxed-queries prompt filter-by]
     (println "lookup-chunks-by-search-phrases" "filter-by:" filter-by)
     (if (or (nil? relaxed-queries) (nil? phrases-collection-name) (nil? prompt))
       (do
         (println "typesenseSearchMultiple() - search terms not provided")
         [])
       (let [typesense-filter (filter-map->typesense-filter filter-by docs-collection-name)
             multi-search-args {:searches (map (fn [query]
                                                 (merge
                                                  {:collection phrases-collection-name
                                                   :q query
                                                   :include_fields "chunk_id,search_phrase"
                                                   :exclude_fields "phrase_vec"
                                                   :limit 20
                                                   :sort_by "_text_match:desc"
                                                   :prioritize_exact_match false
                                                   :drop_tokens_threshold 5}
                                                  (when (not-empty typesense-filter)
                                                    {:filter_by typesense-filter})))
                                               relaxed-queries)}]
         (prn "lookup-search-phrases-similar queries:")
         (prn multi-search-args)
         (let [response (ts-client/multi-search (ts-utils/make-ts-settings) multi-search-args {:query_by "search_phrase,phrase_vec"})
               indexed-search-phrase-hits (->> (:results response)
                                               (mapcat :hits)
                                               (map-indexed (fn [idx phrase]
                                                              (assoc phrase :index idx))))
               chunk-id-list (map (fn [phrase]
                                    (let [rank-val (get-in phrase [:hybrid_search_info :rank_fusion_score])]
                                      {:chunk_id (get-in phrase [:document :chunk_id])
                                       :rank (cond
                                               (nil? rank-val) 0
                                               (number? rank-val) rank-val
                                               (string? rank-val) (try (Double/parseDouble rank-val) (catch Exception _ 0))
                                               :else 0)
                                       :index (:index phrase)}))
                                  indexed-search-phrase-hits)]
           #_(when (= 1 1)
               (prn "lookupSearchPhraseSimilar results:")
               (prn response))
           chunk-id-list)))))

;; search-chunks-by-metadata, search-chunks-by-content, and merge-chunk-search-results
;; functions have been moved earlier in the file before lookup-search-phrases-handler

#?(:clj
   (defn get-typesense-collection
     "Retrieves a Typesense collection by name. Returns nil if collection doesn't exist."
     [collection-name]
     (try
       (ts-client/retrieve-collection (ts-utils/make-ts-settings) collection-name)
       (catch Exception e
         (when-not (= 404 (:status (ex-data e)))
           (throw e))
         nil))))

(comment
  ;;
  (def coll-phrases "KUDOS_next_phrases_ab897fbdedfa")
  (def coll-docs "KUDOS_next_documents_ab897fbdedfa")

  (def test-filter
    {:fields [{:type :multiselect
               :selected-options #{"DFD"}
               :field "orgs_short"}
              {:type :multiselect
               :selected-options #{"Digdir"}
               :field "owner_short"}]})
  (def search-phrases ["Fellesføring 1 Digdir",
                       "Systematisk og helhetlig arbeid Digdir",
                       "redusere klimagassutslipp Digdir",
                       "naturfotavtrykk Digdir",
                       "energibruk Digdir",
                       "klimagassutslipp naturfotavtrykk energibruk Digdir",
                       "Fellesføring 1 klimagassutslipp naturfotavtrykk energibruk"])

  (lookup-chunks-by-search-phrases coll-phrases coll-docs search-phrases "empty prompt - should remove?" test-filter)

  ;; Example usage of get-typesense-collection
  (get-typesense-collection coll-phrases))

#?(:clj
   (defn retrieve-chunks-by-id
     "Retrieve full chunk documents by ID from Typesense.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([docs-collection-name chunks-collection-name chunk-id-list]
      (retrieve-chunks-by-id docs-collection-name chunks-collection-name chunk-id-list nil))
     ([docs-collection-name chunks-collection-name chunk-id-list opts]
      (let [chunk-searches (map (fn [chunk-matches]
                                  {:collection chunks-collection-name
                                   :q (:chunk_id chunk-matches)
                                   :include_fields (str "id,chunk_id,doc_num,content_markdown,metadata,$"
                                                        docs-collection-name "(url,title)")
                                   :filter_by (str "chunk_id:=`" (:chunk_id chunk-matches) "`")
                                   :page 1
                                   :per_page 1})
                                ;; TODO: add minimum check, must be at least xx results, otherwise endpoint returns empty list
                                ;; TODO: use :rerankTopkChunks param from pipeline
                                (take 40 (medley/distinct-by :chunk_id chunk-id-list)))

            ;; TODO: max number of parallel searches for Typesense is 40 - take more than one per search if :rerankTopkChunks > 40
            multi-search-args {:searches chunk-searches
                               :limit_multi_searches 40}
            _ (prn "retrieve-chunks-by-id queries:")
            _ (prn multi-search-args)
            chunk-results (ts-client/multi-search (ts-utils/make-ts-settings opts) multi-search-args {:query_by "chunk_id"})
            ;; Merge search attribution info with retrieved chunks
            chunk-id-to-search-info (into {} (map (juxt :chunk_id identity) chunk-id-list))
            processed-results (->> chunk-results
                                   :results
                                   (mapcat :hits)
                                   (map (fn [hit]
                                          (let [doc (:document hit)
                                                search-info (get chunk-id-to-search-info (:chunk_id doc))]
                                            (assoc doc
                                                   :search-types (:search-types search-info)
                                                   :hit-count (:hit-count search-info)
                                                   :original-rank (:rank search-info)
                                                   :original-index (:index search-info))))))]
        processed-results))))

#?(:clj
   (defn system-prompt-with-date
     "Generate a system prompt that includes the current date in Norwegian time"
     []
     (let [norway-tz (java.time.ZoneId/of "Europe/Oslo")
           now (java.time.ZonedDateTime/now norway-tz)
           formatter (java.time.format.DateTimeFormatter/ofPattern "d. MMMM yyyy" (java.util.Locale. "no" "NO"))
           norwegian-date (.format now formatter)]
       (str "You are a helpful assistant. The date this conversation was created at is " norwegian-date " (Norwegian time)."))))

#?(:clj
   (defn rag-generate [!dh-conn convo-id extract-search-queries full-prompt params]
     (let [start-time (System/currentTimeMillis)
           selected-model (or (:selected-model params)
                               (if (llm/use-azure-openai)
                                 (cfg/get :services :azure-openai :deployment-name)
                                 (cfg/get :services :azure-openai :model-name)))
           _ (prn (str "\n\n*** Starting RAG structured output chain, llm: " selected-model "\n\n"))
           _ (prn (str "\n\nfull-prompt: \n" full-prompt))
           chat-response
           (if (llm/use-azure-openai)
             (openai/create-chat-completion
              {:model selected-model
               :messages [{:role "system" :content (system-prompt-with-date)}
                          {:role "user" :content full-prompt}]
               :temperature 0.1
               :max_tokens nil}
              {:api-key (cfg/get :services :azure-openai :api-key)
               :api-endpoint (cfg/get :services :azure-openai :api-endpoint)
               :impl :azure})
             (openai/create-chat-completion
              {:model selected-model
               :messages [{:role "system" :content (system-prompt-with-date)}
                          {:role "user" :content full-prompt}]
               :temperature 0.1
               :stream false
               :max_tokens nil}))
           end-time (System/currentTimeMillis)
           duration (- end-time start-time)
           _ (println (str "RAG query duration: " duration " ms"))
           assistant-reply (:content (:message (first (:choices chat-response))))
           english-answer (or assistant-reply "")
           translated-answer english-answer
           rag-success true
           ;; durations (assoc durations :rag_query (round (lap-timer start)))
           translation-enabled false
           ;; durations (assoc durations :translation (round (lap-timer start)))
           ;; durations (assoc durations :total (round (lap-timer total-start)))

           response {:conversation-id convo-id
                     :entity-id (:entity-id params)
                     :original_user_query (:original_user_query params)
                     :english_user_query (:translated_user_query params)
                     :user_query_language_name (:user_query_language_name params)
                     :english_answer english-answer
                     :translated_answer translated-answer
                     :rag_success rag-success
                     :search_queries (or (:searchQueries extract-search-queries) [])
                     :relevant_urls []
                     ;; :not_loaded_urls not-loaded-urls
                     ;;  :durations durations
                     :prompts {:queryRelax (or (:promptRagQueryRelax params) "")
                               :generate (or (:promptRagGenerate params) "")
                               :fullPrompt full-prompt}}
           ;; Get one-line summary of original query

           ;;
           ]
       ;;

       response)))

#?(:clj
   (defn simplify-convo-topic [params]
     (let [selected-model (or (:selected-model params)
                              (if (llm/use-azure-openai)
                                (cfg/get :services :azure-openai :deployment-name)
                                (cfg/get :services :azure-openai :model-name)))
           summary-response
           (if (llm/use-azure-openai)
             (openai/create-chat-completion
              {:model selected-model
               :messages [{:role "system"
                           :content "Provide a 3 to 5 word summary of the user's query, use the same language as the user."}
                          {:role "user"
                           :content (str "<USER_QUERY>" (:original_user_query params) "</USER_QUERY>")}]
               :temperature 0.1
               :max_tokens 30}
              {:api-key (cfg/get :services :azure-openai :api-key)
               :api-endpoint (cfg/get :services :azure-openai :api-endpoint)
               :impl :azure
               ;; :trace (fn [request response]
               ;;          #_(println "Request:" request)
               ;;          (println "Response:" response))
               })
             (openai/create-chat-completion
              {:model selected-model
               :messages [{:role "system"
                           :content "Provide a 3 to 5 word summary of the user's query, use the same language as the user."}
                          {:role "user"
                           :content (str "<USER_QUERY>" (:original_user_query params) "</USER_QUERY>")}]
               :temperature 0.1
               :max_tokens 30}))
           _ (println (str "summary: ") summary-response)
           summary (-> summary-response :choices first :message :content)
           _ (println "Query summary:" summary)
           _ (db/rename-convo-topic (db/get-conn) (:conversation-id params) summary)])))

#?(:clj
   (defn rerank-chunks
     [retrieved-chunks params]
     (let [all-chunk-ids (atom [])
           all-docs (atom [])
           loaded-docs (atom [])
           loaded-chunk-ids (atom [])
           loaded-search-hits (atom [])
           doc-index (atom 0)
           docs-length (atom 0)]
       ;; Make list of all markdown content
       (while (< @doc-index (count retrieved-chunks))
         (let [search-hit (nth retrieved-chunks @doc-index)
               ;;  _ (prn-str (str "search hit #" @doc-index ": ") search-hit)
               unique-chunk-id (:chunk_id search-hit)
               doc-md (:content_markdown search-hit)]
           (swap! doc-index inc)
           (when (and doc-md
                      (not (some #(= unique-chunk-id %) @all-chunk-ids)))
             (let [loaded-doc {:page_content doc-md
                               :metadata {:source unique-chunk-id}}]
               (swap! all-docs conj loaded-doc)
               (swap! all-chunk-ids conj unique-chunk-id)))))

       ;; Rerank results using ColBERT
       (let [rerank-url (cfg/get :services :colbert :api-url)
             rerank-api-key (cfg/get :services :colbert :api-key)
             rerank-api-max-input-length 1000]
         (when (nil? rerank-url)
           (throw (ex-info (str "Environment variable 'COLBERT_API_URL' is invalid: '" rerank-url "'") {})))
         (let [user-input (subs (:translated_user_query params)
                                0 (min (count (:translated_user_query params)) rerank-api-max-input-length))
               rerank-data {:user_input user-input
                            :k (:rerankTopkChunks params)
                            :documents (map
                                        (fn [doc]
                                          (let [title (get-in doc [(keyword (:docsCollectionName params)) :title])
                                                metadata (:metadata doc)
                                                content (:content_markdown doc)
                                                ;; Combine title, metadata, and content for reranking
                                                combined-text (str (when title (str "Title: " title "\n\n"))
                                                                   (when metadata (format-metadata-headers metadata))
                                                                   content)
                                                truncated (if (> (count combined-text) (:rerankMaxChunkLength params))
                                                            (subs combined-text 0 (:rerankMaxChunkLength params))
                                                            combined-text)]
                                            truncated))
                                        (take (:rerankTopkChunks params) retrieved-chunks))}
               rerank-data (json/write-str rerank-data)
               _ (println (str "Rerank data: " rerank-data))
               _ (println (str "Rerank data payload length: " (count rerank-data)))
               rerank-response (http/post rerank-url {:body rerank-data
                                                      :content-type :json
                                                      :headers {"X-API-Key" rerank-api-key}})
               ;;  _ (println (str "Rerank response: " rerank-response))
               rerank-response-body (json/read-str (:body rerank-response) :key-fn keyword)
               _ (when (empty? rerank-response-body)
                   (println "***  Warning: Rerank response was empty, falling back to original ranking ***"))
               search-hits-reranked (if (empty? rerank-response-body)
                                      retrieved-chunks ; fallback to original ranking
                                      (keep #(when-let [idx (:index %)]
                                               (when (and (number? idx)
                                                          (>= idx 0)
                                                          (< idx (count retrieved-chunks)))
                                                 (nth retrieved-chunks idx)))
                                            rerank-response-body))

               ;; Log reranking changes
               _ (println "\n========== RERANKING ANALYSIS ==========")
               _ (println (str "Total chunks before reranking: " (count retrieved-chunks)
                               " - took: " (:rerankTopkChunks params)
                               " - after rerank: " (count search-hits-reranked)))
               _ (doseq [[new-pos chunk] (map-indexed vector (take (:rerankTopkChunks params) search-hits-reranked))]
                   (let [old-pos (.indexOf retrieved-chunks chunk)
                         title (get-in chunk [(keyword (:docsCollectionName params)) :title])
                         change (- old-pos new-pos)]
                     (println (str "\n#" (inc new-pos) " Chunk ID: " (:chunk_id chunk)))
                     (println (str "   Title: " title))
                     (println (str "   Position change: " old-pos " → " new-pos
                                   " (" (if (pos? change) (str "+" change) change) ")"))
                     (println (str "   Found by: " (str/join ", " (map name (:search-types chunk)))))
                     (println (str "   Metadata: " (:metadata chunk)))
                     (println (str "   Content preview: " (subs (or (:content_markdown chunk) "") 0
                                                                (min 200 (count (:content_markdown chunk))))
                                   "..."))))
               _ (println "=========================================\n")]
           #_(swap! durations assoc :colbert_rerank (round (lap-timer start)))

           ;; Need to preserve order in chunks list
           (reset! doc-index 0)
           (while (and (< @doc-index (count search-hits-reranked))
                       (or (< @docs-length (:maxContextLength params))
                           (< (count @loaded-docs) (:contextTopkChunks params))))
             (let [search-hit (nth search-hits-reranked @doc-index)
                   unique-chunk-id (:chunk_id search-hit)
                   doc-md (:content_markdown search-hit)
                   title (get-in search-hit [(keyword (:docsCollectionName params)) :title])
                   metadata (:metadata search-hit)
                   metadata-str (when metadata
                                  (format-metadata-headers metadata))
                   source-desc (str
                                "\n```\nTitle: " title
                                (when metadata-str (str "\n" metadata-str))
                                "\n```\n\n")
                   doc-trimmed (if (> (count doc-md) (:contextMaxChunkLength params))
                                 (subs doc-md 0 (:contextMaxChunkLength params))
                                 doc-md)]
               (swap! doc-index inc)
               (when (and doc-trimmed
                          (not (some #(= unique-chunk-id %) @loaded-chunk-ids)))
                 (let [full-content (str source-desc doc-trimmed)
                       loaded-doc {:page_content full-content
                                   :metadata {:source unique-chunk-id}}]
                   (swap! docs-length + (count full-content))
                   (swap! loaded-docs conj loaded-doc)
                   (swap! loaded-chunk-ids conj unique-chunk-id)
                   (swap! loaded-search-hits conj search-hit)
                   ;; TODO: use rerankMaxLength - should always rerank more than we can use in llm context window
                   (when (>= @docs-length (:rerankMaxLength params))
                     (println (str "Rerank payload size limit reached, loaded " (count @loaded-docs) " chunks")))
                   (when (>= (count @loaded-docs) (:rerankTopkChunks params))
                     (println (str "rerankTopkChunks limit reached, loaded " (count @loaded-docs) " chunks")))))))

           (let [context-yaml (str/join "\n\n" (map :page_content @loaded-docs))
                 _ (println-str "\n\n******** context-yaml *********  :\n\n" context-yaml)
                 partial-prompt (:promptRagGenerate params)
                 ;; Only build full prompt if promptRagGenerate is provided (not needed for retrieval-only)
                 full-prompt (when partial-prompt
                               (-> partial-prompt
                                   (str/replace "{context}" context-yaml)
                                   (str/replace "{question}" (:translated_user_query params))))
                 chunks-by-id (medley/index-by :chunk_id retrieved-chunks)
                 used-chunks-final (mapv chunks-by-id @loaded-chunk-ids)

                 ;; Log final chunks used for generation
                 _ (println "\n========== FINAL CHUNKS USED FOR GENERATION ==========")
                 _ (println (str "Total chunks in context: " (count used-chunks-final)))
                 _ (println (str "Total context length: " @docs-length " characters"))
                 _ (doseq [[idx chunk] (map-indexed vector used-chunks-final)]
                     (let [title (get-in chunk [(keyword (:docsCollectionName params)) :title])
                           rerank-pos (.indexOf search-hits-reranked chunk)]
                       (println (str "\n#" (inc idx) " Chunk ID: " (:chunk_id chunk)))
                       (println (str "   Title: " title))
                       (println (str "   Reranked position: " (inc rerank-pos)))
                       (println (str "   Original search rank: " (:original-rank chunk)))
                       (println (str "   Found by: " (str/join ", " (map name (:search-types chunk)))))
                       (println (str "   Metadata: " (:metadata chunk)))
                       (println (str "   Content length: " (count (:content_markdown chunk)) " chars"))
                       (println (str "   Content preview: " (subs (or (:content_markdown chunk) "") 0
                                                                  (min 150 (count (:content_markdown chunk))))
                                     "..."))))
                 _ (println "======================================================\n")]
             {:used-chunks used-chunks-final
              :used-docs @loaded-docs
              :full-prompt full-prompt}))))))

;; State machine execution engine

#?(:clj
   (defn transition-to [state-machine next-state context]
     (if (nil? next-state)
       state-machine
       (-> state-machine
           (assoc :current-state next-state)
           (assoc :context context)))))

#?(:clj
   (defn execute-state [state-machine]
     (let [current-state (:current-state state-machine)
           state-config (get-in state-machine [:states current-state])
           handler (:handler state-config)
           context (:context state-machine)]
       (if (nil? handler)
         (do
           (println (str "No handler for state: " current-state))
           (update state-machine :errors conj {:no-handler current-state}))
         (try
           (println :start current-state (:description state-config))
           (let [{:keys [next updated-context]} (handler context)]
             (println :end current-state)
             (transition-to state-machine (or next (:next state-config)) updated-context))
           (catch Exception e
             (println :error current-state ":" (.getMessage e))
             (-> state-machine
                 (update :errors conj {:state current-state
                                       :error (.getMessage e)
                                       :exception e})
                 (assoc :current-state :complete))))))))

#?(:clj
   (defn run-state-machine [state-machine]
     (loop [sm state-machine]
       (if (or (= :complete (:current-state sm))
               (not-empty (:errors sm)))
         sm
         (recur (execute-state sm))))))

;; Define handlers for each state in the RAG pipeline
#?(:clj
   (defn init-handler [{:keys [params !dh-conn] :as context}]
     (let [convo-id (:conversation-id params)]
       (println "\n========== STARTING RAG PIPELINE ==========")
       (println (str "User query: \"" (:original_user_query params) "\""))
       (println (str "Conversation ID: " convo-id))
       (println "===========================================\n")
       {:updated-context (assoc context :convo-id convo-id)})))

#?(:clj
   (defn fetch-messages-handler [{:keys [params !dh-conn convo-id] :as context}]
     (db/transact-user-msg !dh-conn convo-id (:original_user_query params))

     (let [messages (vec (db/fetch-convo-messages-mapped @!dh-conn convo-id))
           filter-messages (filterv #(some? (:message.filter/value %)) messages)
           filter-by (-> (last filter-messages) :message.filter/value)

           retrieval-prompt-msg (first (filter #(and (= :user (:message/role %))
                                                     (= :agent (:message/voice %))
                                                     (= true (:message/completion %))
                                                     (= :kind/markdown (:message/kind %)))
                                               messages))
           retrieval-prompt-msg-id (when retrieval-prompt-msg (:message/id retrieval-prompt-msg))

           retrieved-sources-msg (first (filter #(and (= :system (:message/role %))
                                                      (= :assistant (:message/voice %))
                                                      (= false (:message/completion %))
                                                      (= :kind/html (:message/kind %)))
                                                messages))
           retrieved-sources-msg-id (when retrieved-sources-msg (:message/id retrieved-sources-msg))

           completion-messages (filter #(not= false (:message/completion %))
                                       messages)]

       (prn "Fetched" (count completion-messages) "completion-messages")

       {:updated-context (assoc context
                                :messages messages
                                :filter-by filter-by
                                :retrieval-prompt-msg-id retrieval-prompt-msg-id
                                :retrieved-sources-msg-id retrieved-sources-msg-id
                                :completion-messages completion-messages)})))

#?(:clj
   (defn query-relaxation-handler [{:keys [params !dh-conn completion-messages] :as context}]
     (update-response-state! (:conversation-id params) "Ser etter dokumenter")
     (let [extract-search-queries (query-relaxation (:promptRagQueryRelax params) completion-messages (:selected-model params))]
       {:updated-context (assoc context :extract-search-queries extract-search-queries)})))

#?(:clj
   (defn lookup-search-phrases-handler [{:keys [params !dh-conn extract-search-queries filter-by] :as context}]
     (let [;; Run all three search methods in parallel
           search-phrase-hits (lookup-search-phrases-similar
                               (:phrasesCollectionName params)
                               (:docsCollectionName params)
                               extract-search-queries
                               (:phrase-gen-prompt params)
                               filter-by)

           metadata-hits (search-chunks-by-metadata
                          (:chunksCollectionName params)
                          (:docsCollectionName params)
                          extract-search-queries
                          filter-by)

           content-hits (search-chunks-by-content
                         (:chunksCollectionName params)
                         (:docsCollectionName params)
                         extract-search-queries
                         filter-by)

           ;; Merge all results preserving search type information
           merged-hits (merge-chunk-search-results
                        (map #(assoc % :search-type :phrase) search-phrase-hits)
                        metadata-hits
                        content-hits)]

       (println "Search results - phrases:" (count search-phrase-hits)
                "metadata:" (count metadata-hits)
                "content:" (count content-hits)
                "merged unique:" (count merged-hits))

       ;; Log detailed search attribution for top chunks
       (println "\n========== SEARCH ATTRIBUTION (Top 10 Chunks) ==========")
       (doseq [[idx chunk] (map-indexed vector (take 10 merged-hits))]
         (println (str "\n#" (inc idx) " Chunk ID: " (:chunk_id chunk)))
         (println (str "   Found by: " (str/join ", " (map name (:search-types chunk)))))
         (println (str "   Hit count: " (:hit-count chunk)))
         (println (str "   Best rank: " (:rank chunk))))
       (println "=========================================================\n")

       (if (empty? merged-hits)
         (do
           (update-response-state! (:conversation-id params) "Ingen søkefraser funnet")
           (throw (ex-info "No search results found" {})))

         {:updated-context (assoc context
                                  :search-phrase-hits merged-hits
                                  :search-attribution {:phrase (count search-phrase-hits)
                                                       :metadata (count metadata-hits)
                                                       :content (count content-hits)
                                                       :merged (count merged-hits)})}))))

#?(:clj
   (defn retrieve-chunks-handler [{:keys [params !dh-conn search-phrase-hits] :as context}]
     (println "Retrieving docs from" (:docsCollectionName params) "and chunks from" (:chunksCollectionName params))
     (let [retrieved-chunks (retrieve-chunks-by-id
                             (:docsCollectionName params)
                             (:chunksCollectionName params)
                             search-phrase-hits)]
       (println "Chunks retrieved count:" (count retrieved-chunks))
       ;;  (println retrieved-chunks)
       (if (empty? retrieved-chunks)
         (do
           (update-response-state! (:conversation-id params) "Ingen kilder funnet")
           (throw (ex-info "No sources found" {})))
         {:updated-context (assoc context :retrieved-chunks retrieved-chunks)}))))

#?(:clj
   (defn rerank-chunks-handler [{:keys [params !dh-conn retrieved-chunks search-attribution] :as context}]
     (update-response-state! (:conversation-id params) "Sorterer rekkefølgen")

     (let [{:keys [used-chunks used-docs full-prompt]} (rerank-chunks retrieved-chunks params)
           ;; Calculate search type distribution for used chunks
           search-type-distribution (reduce (fn [acc chunk]
                                              (let [search-types (:search-types chunk)]
                                                (reduce (fn [acc2 search-type]
                                                          (update acc2 search-type (fnil inc 0)))
                                                        acc
                                                        search-types)))
                                            {}
                                            used-chunks)

           ;; Emit telemetry signal for search distribution
           ;; TODO: This signal should be visualized in the admin dashboard
           ;; Signal includes: search-attribution (counts per search type)
           ;; and search-type-distribution (which chunks made it to top K)
           _ (when (resolve 'taoensso.telemere/signal!)
               ((resolve 'taoensso.telemere/signal!)
                :info
                :rag/search-distribution
                {:conversation-id (:conversation-id params)
                 :entity-id (:entity-id params)
                 :search-attribution search-attribution
                 :search-type-distribution search-type-distribution
                 :total-chunks-used (count used-chunks)
                 :chunks-per-search-type (into {}
                                               (map (fn [[k v]]
                                                      [k (/ v (count used-chunks))])
                                                    search-type-distribution))}))

           ;; Log search distribution summary
           _ (println "\n========== SEARCH DISTRIBUTION SUMMARY ==========")
           _ (println (str "Total chunks used: " (count used-chunks)))
           _ (doseq [[search-type type-count] search-type-distribution]
               (let [percentage (* 100.0 (/ type-count (count used-chunks)))]
                 (println (str "   " (name search-type) ": " type-count " chunks ("
                               (format "%.1f" percentage) "%)"))))
           _ (println "=================================================\n")]

       (when (empty? used-docs)
         (println "***** Error: No documents were loaded during reranking. *****")) ;; TODO emit state machine error
       {:updated-context (assoc context
                                :used-chunks used-chunks
                                :used-docs used-docs
                                :full-prompt full-prompt
                                :search-type-distribution search-type-distribution)})))

#?(:clj
   (defn generate-response-handler [{:keys [params !dh-conn convo-id extract-search-queries full-prompt
                                            used-chunks search-type-distribution] :as context}]
     (update-response-state! (:conversation-id params) "Skriver svar")

     ;; Log detailed information about chunks being used for generation
     (println "\n========== CHUNKS USED FOR GENERATION ==========")
     (println (str "Total chunks: " (count used-chunks)))
     (doseq [[idx chunk] (map-indexed vector used-chunks)]
       (println (str "\n#" (inc idx) " Chunk ID: " (:chunk_id chunk)))
       (println (str "   Document: " (get-in chunk [(:docsCollectionName params) :title])))
       (println (str "   Search types: " (str/join ", " (map name (:search-types chunk)))))
       (println (str "   Rank: " (:rank chunk)))
       (when-let [metadata (:metadata chunk)]
         (if-let [formatted-metadata (format-metadata-headers metadata)]
           (println (str "   Metadata headers:\n      " (str/replace formatted-metadata "\n" "\n      ")))
           (println (str "   Raw metadata: " metadata)))))

     ;; Log search type distribution
     (println "\n--- Search Type Contribution ---")
     (doseq [[search-type percentage] (:chunks-per-search-type search-type-distribution)]
       (println (str "   " (name search-type) ": " (format "%.1f%%" (* 100 percentage)))))
     (println "================================================\n")

     (let [generation-result (rag-generate !dh-conn convo-id extract-search-queries full-prompt params)
           ;; Add chunks to the generation result
           result-with-chunks (assoc generation-result :chunks used-chunks)]
       {:updated-context (assoc context :generation-result result-with-chunks)})))

#?(:clj
   (defn transact-assistant-msg-handler [{:keys [params !dh-conn convo-id generation-result
                                                 extract-search-queries used-chunks] :as context}]
     (let [assistant-msg-id (:message/id
                             (db/transact-assistant-msg !dh-conn convo-id (:english_answer generation-result)))
           ;;  _ (println "Will transact the following chunks: " used-chunks)
           ]
       (db/transact-used-data !dh-conn assistant-msg-id extract-search-queries (:docsCollectionName params) used-chunks)
       {:updated-context (assoc context :assistant-msg-id assistant-msg-id)})))

#?(:clj
   (defn simplify-conversation-topic-handler [{:keys [params] :as context}]
     (simplify-convo-topic params)
     {:updated-context (assoc context :result (:generation-result context))}))

;; Define the state machine structure for the RAG pipeline
#?(:clj
   (defn create-rag-state-machine []
     {:states {:init {:description "Initialize the RAG pipeline"
                      :handler init-handler
                      :next :fetch-messages}
               :fetch-messages {:description "Fetch conversation messages"
                                :handler fetch-messages-handler
                                :next :query-relaxation}
               :query-relaxation {:description "Extract search queries from conversation"
                                  :handler query-relaxation-handler
                                  :next :lookup-search-phrases}
               :lookup-search-phrases {:description "Find relevant search phrases"
                                       :handler lookup-search-phrases-handler
                                       :next :retrieve-chunks}
               :retrieve-chunks {:description "Retrieve document chunks based on search phrases"
                                 :handler retrieve-chunks-handler
                                 :next :rerank-chunks}
               :rerank-chunks {:description "Rerank chunks by relevance"
                               :handler rerank-chunks-handler
                               :next :generate-response}
               :generate-response {:description "Generate response using retrieved documents"
                                   :handler generate-response-handler
                                   :next :transact-assistant-msg}
               :transact-assistant-msg {:description "Save assistant message to database"
                                        :handler transact-assistant-msg-handler
                                        :next :simplify-conversation-topic}
               :simplify-conversation-topic {:description "Create a simplified topic for the conversation"
                                             :handler simplify-conversation-topic-handler
                                             :next :complete}
               :complete {:description "Complete the RAG pipeline"
                          :next nil}}
      :current-state :init
      :context {}
      :errors []
      :result nil}))

;; The main RAG pipeline function using the state machine
#?(:clj
   (defn rag-pipeline [params !dh-conn]
     ;; !dh-conn is ofc unserialiable
     (let [state-machine (-> (create-rag-state-machine)
                             (assoc :context {:params params
                                              :!dh-conn !dh-conn}))
           final-state-machine (run-state-machine state-machine)]

       (if (not-empty (:errors final-state-machine))
         (if-let [error (-> final-state-machine :errors first :exception)]
           (throw error)
           (throw (ex-info "State machine failed" {:state-machine-errors (:errors final-state-machine)})))

         (get-in final-state-machine [:context :generation-result])))))

;; ===== Retrieval-Only Pipeline =====
;; A lightweight pipeline that returns ranked chunks without LLM generation.
;; Useful for clients who want to handle their own generation.

#?(:clj
   (defn init-retrieval-handler
     "Initialize the retrieval pipeline. Simpler than init-handler - no conversation context."
     [{:keys [params] :as context}]
     (println "\n========== STARTING RETRIEVAL PIPELINE ==========")
     (println (str "User query: \"" (:original_user_query params) "\""))
     (println (str "Query expansion enabled: " (:include-query-expansion params)))
     (println "===========================================\n")
     {:updated-context context}))

#?(:clj
   (defn query-relaxation-retrieval-handler
     "Extract search queries from the user query. Optionally uses LLM to expand the query."
     [{:keys [params] :as context}]
     (if (:include-query-expansion params)
       ;; Use LLM to expand the query
       (let [synthetic-messages [{:message/role :user
                                  :message/text (:original_user_query params)}]
             extract-search-queries (query-relaxation (:promptRagQueryRelax params)
                                                      synthetic-messages
                                                      (:selected-model params))]
         (println "Expanded queries:" extract-search-queries)
         {:updated-context (assoc context :extract-search-queries extract-search-queries)})
       ;; Skip query expansion - use original query directly
       (do
         (println "Query expansion disabled, using original query")
         {:updated-context (assoc context :extract-search-queries [(:original_user_query params)])}))))

#?(:clj
   (defn rerank-chunks-retrieval-handler
     "Rerank chunks and return them without building an LLM prompt.
      Returns chunks enriched with rerank position information."
     [{:keys [params retrieved-chunks search-attribution] :as context}]
     (let [{:keys [used-chunks]} (rerank-chunks retrieved-chunks params)
           ;; Enrich chunks with rerank position for the response
           enriched-chunks (map-indexed
                            (fn [idx chunk]
                              (assoc chunk :rerank-position (inc idx)))
                            used-chunks)]
       (println (str "Returning " (count enriched-chunks) " reranked chunks"))
       {:updated-context (assoc context
                                :result-chunks enriched-chunks
                                :search-attribution search-attribution)})))

#?(:clj
   (defn create-retrieval-state-machine
     "Create a state machine for retrieval-only operations.
      This is a lightweight version that stops before LLM generation."
     []
     {:states {:init {:description "Initialize the retrieval pipeline"
                      :handler init-retrieval-handler
                      :next :query-relaxation}
               :query-relaxation {:description "Expand search queries (optional)"
                                  :handler query-relaxation-retrieval-handler
                                  :next :lookup-search-phrases}
               :lookup-search-phrases {:description "Multi-strategy search"
                                       :handler lookup-search-phrases-handler
                                       :next :retrieve-chunks}
               :retrieve-chunks {:description "Fetch chunk content"
                                 :handler retrieve-chunks-handler
                                 :next :rerank-chunks}
               :rerank-chunks {:description "Rerank chunks by relevance"
                               :handler rerank-chunks-retrieval-handler
                               :next :complete}
               :complete {:description "Complete retrieval pipeline"
                          :next nil}}
      :current-state :init
      :context {}
      :errors []
      :result nil}))

#?(:clj
   (defn retrieval-pipeline
     "Execute the retrieval-only pipeline.
      Returns {:chunks [...] :expanded-queries [...] :search-attribution {...}}

      Required params:
        :original_user_query - The user's search query
        :include-query-expansion - Whether to use LLM query expansion (default true)
        :docsCollectionName - Typesense docs collection
        :chunksCollectionName - Typesense chunks collection
        :phrasesCollectionName - Typesense phrases collection

      Optional params:
        :promptRagQueryRelax - Prompt for query expansion
        :selected-model - Model for query expansion
        :rerankTopkChunks - Max chunks to rerank
        :contextTopkChunks - Max chunks to return
        :rerankMaxChunkLength - Max length per chunk for reranking
        :rerankMaxLength - Max total length for reranking
        :maxContextLength - Max total context length
        :filter-by - Filter criteria for search"
     [params]
     (let [state-machine (-> (create-retrieval-state-machine)
                             (assoc :context {:params params}))
           final-state-machine (run-state-machine state-machine)]
       (if (not-empty (:errors final-state-machine))
         (if-let [error (-> final-state-machine :errors first :exception)]
           (throw error)
           (throw (ex-info "Retrieval pipeline failed"
                           {:errors (:errors final-state-machine)})))
         ;; Return retrieval results
         {:chunks (get-in final-state-machine [:context :result-chunks])
          :expanded-queries (get-in final-state-machine [:context :extract-search-queries])
          :search-attribution (get-in final-state-machine [:context :search-attribution])}))))
;;
