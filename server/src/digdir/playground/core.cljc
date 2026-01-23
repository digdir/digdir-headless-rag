(ns digdir.playground.core
  "Backend logic for the RAG Playground feature.
   Exposes RAG pipeline stages individually for debugging and experimentation."
  (:require #?(:clj [digdir.rag.core :as rag])
            #?(:clj [digdir.rag.typesense :as ts-utils])
            #?(:clj [typesense.client :as ts-client])
            #?(:clj [digdir.config.accessor :as cfg])
            #?(:clj [digdir.config.core])
            #?(:clj [digdir.config.db])
            #?(:clj [digdir.data.db :as db])
            #?(:clj [digdir.llm.openai :as llm])
            #?(:clj [wkok.openai-clojure.api :as openai])
            #?(:clj [nano-id.core :refer [nano-id]])
            #?(:clj [clojure.data.json :as json])
            #?(:clj [clj-http.client :as http])
            #?(:clj [taoensso.telemere :as t])
            [clojure.string :as str]))

;; Execution state atom - stores results from each pipeline stage
#?(:clj (defonce !playground-executions (atom {})))

;; Current execution ID - tracked on server for reactive UI updates
#?(:clj (defonce !current-execution-id (atom nil)))

#?(:clj
   (defn get-entity-by-id
     "Get entity configuration by ID.
      Delegates to cfg/get-entity which supports both DB mode and legacy EDN fallback."
     [entity-id]
     (cfg/get-entity entity-id)))

#?(:clj
   (defn fetch-chunk-by-id
     "Fetch full chunk data from Typesense by chunk_id.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([chunks-collection docs-collection chunk-id]
      (fetch-chunk-by-id chunks-collection docs-collection chunk-id nil))
     ([chunks-collection docs-collection chunk-id opts]
      (when (and chunks-collection chunk-id)
        (let [search-args {:searches [{:collection chunks-collection
                                       :q chunk-id
                                       :query_by "chunk_id"
                                       :filter_by (str "chunk_id:=`" chunk-id "`")
                                       :include_fields (str "id,chunk_id,doc_num,content_markdown,metadata,$"
                                                            docs-collection "(url,title)")
                                       :per_page 1}]}
              response (ts-client/multi-search (ts-utils/make-ts-settings opts) search-args {:query_by "chunk_id"})
              hits (get-in response [:results 0 :hits])]
          (when (seq hits)
            (:document (first hits))))))))

;; =========Typesense Diagnostics=========

#?(:clj
   (defn list-typesense-collections
     "List all available Typesense collections.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([] (list-typesense-collections nil))
     ([opts]
      (try
        (let [ts-settings (ts-utils/make-ts-settings opts)
              collections (ts-client/list-collections ts-settings)]
          (mapv :name collections))
        (catch Exception e
          (t/log! :warn [:typesense/list-collections-failed {:error (.getMessage e)}])
          nil)))))

#?(:clj
   (defn check-typesense-connection
     "Check if Typesense is reachable and return connection info.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([] (check-typesense-connection nil))
     ([opts]
      (try
        (let [ts-settings (ts-utils/make-ts-settings opts)
              health (ts-client/health ts-settings)]
          {:connected true
           :uri (:uri ts-settings)
           :health health})
        (catch Exception e
          {:connected false
           :uri (:uri (ts-utils/make-ts-settings opts))
           :error (.getMessage e)
           :error-type (str (type e))})))))

#?(:clj
   (defn get-typesense-diagnostics
     "Get diagnostic information about Typesense configuration.
      Checks connection, lists collections, and validates expected collections exist.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([expected-collections] (get-typesense-diagnostics expected-collections nil))
     ([expected-collections opts]
      (let [connection (check-typesense-connection opts)
            available-collections (when (:connected connection)
                                    (list-typesense-collections opts))
            available-set (set available-collections)
            missing-collections (when available-collections
                                  (filterv #(not (contains? available-set %))
                                           (remove nil? expected-collections)))]
        (merge connection
               {:available-collections available-collections
                :expected-collections (vec (remove nil? expected-collections))
                :missing-collections missing-collections
                :all-collections-exist (empty? missing-collections)})))))

#?(:clj
   (defn update-execution!
     "Update execution state"
     [execution-id updates]
     (swap! !playground-executions update execution-id merge updates)))

#?(:clj
   (defn update-execution-results!
     "Update specific results in execution state"
     [execution-id result-key value]
     (swap! !playground-executions assoc-in [execution-id :results result-key] value)))

#?(:clj
   (defn append-streaming-content!
     "Append content to streaming response"
     [execution-id content]
     (swap! !playground-executions update-in [execution-id :streaming-content] str content)))

;; Individual pipeline stage functions

#?(:clj
   (defn execute-query-relaxation
     "Execute query relaxation stage - converts user query to search phrases"
     [execution-id query prompt-rag-query-relax]
     (update-execution! execution-id {:stage :query-relax})
     (let [;; Create a mock message list with just the user query
           messages [{:message/role :user
                      :message/text query}]
           search-phrases (rag/query-relaxation prompt-rag-query-relax messages nil)]
       (update-execution-results! execution-id :query-relaxation search-phrases)
       search-phrases)))

#?(:clj
   (defn execute-query-relaxation-with-history
     "Execute query relaxation with full conversation history for context.
      Messages should be a vector of {:message/role :user|:assistant, :message/text \"...\"}."
     [execution-id messages prompt-rag-query-relax]
     (update-execution! execution-id {:stage :query-relax})
     (let [search-phrases (rag/query-relaxation prompt-rag-query-relax messages nil)]
       (update-execution-results! execution-id :query-relaxation search-phrases)
       search-phrases)))

#?(:clj
   (defn execute-phrase-search
     "Execute phrase/semantic search.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([execution-id phrases-collection docs-collection search-phrases prompt filter-by]
      (execute-phrase-search execution-id phrases-collection docs-collection search-phrases prompt filter-by nil))
     ([execution-id phrases-collection docs-collection search-phrases prompt filter-by opts]
      (update-execution! execution-id {:stage :phrase-search})
      (let [results (rag/lookup-search-phrases-similar
                     phrases-collection
                     docs-collection
                     search-phrases
                     prompt
                     filter-by
                     opts)
            ;; Add search-type to results
            results-with-type (map #(assoc % :search-type :phrase) results)]
        (update-execution-results! execution-id :phrase-search results-with-type)
        results-with-type))))

#?(:clj
   (defn execute-metadata-search
     "Execute metadata BM25 search.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([execution-id chunks-collection docs-collection search-phrases filter-by]
      (execute-metadata-search execution-id chunks-collection docs-collection search-phrases filter-by nil))
     ([execution-id chunks-collection docs-collection search-phrases filter-by opts]
      (update-execution! execution-id {:stage :metadata-search})
      (let [results (rag/search-chunks-by-metadata
                     chunks-collection
                     docs-collection
                     search-phrases
                     filter-by
                     opts)
            ;; Normalize scores for display (raw BM25 scores are huge 64-bit encoded values)
            normalized-results (vec (rag/normalize-ranks results))]
        (update-execution-results! execution-id :metadata-search normalized-results)
        normalized-results))))

#?(:clj
   (defn execute-content-search
     "Execute content BM25 search.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([execution-id chunks-collection docs-collection search-phrases filter-by]
      (execute-content-search execution-id chunks-collection docs-collection search-phrases filter-by nil))
     ([execution-id chunks-collection docs-collection search-phrases filter-by opts]
      (update-execution! execution-id {:stage :content-search})
      (let [results (rag/search-chunks-by-content
                     chunks-collection
                     docs-collection
                     search-phrases
                     filter-by
                     opts)
            ;; Normalize scores for display (raw BM25 scores are huge 64-bit encoded values)
            normalized-results (vec (rag/normalize-ranks results))]
        (update-execution-results! execution-id :content-search normalized-results)
        normalized-results))))

#?(:clj
   (defn execute-merge-results
     "Merge results from all search types, optionally filtering by threshold"
     [execution-id phrase-results metadata-results content-results rerank-threshold]
     (update-execution! execution-id {:stage :merge})
     (let [merged (rag/merge-chunk-search-results
                   phrase-results
                   metadata-results
                   content-results)
           ;; Apply threshold filter if specified
           filtered (if rerank-threshold
                      (let [above-threshold (filterv #(>= (:rank %) rerank-threshold) merged)]
                        (t/log! :info [:playground/threshold-applied
                                       {:threshold rerank-threshold
                                        :before-filter (count merged)
                                        :after-filter (count above-threshold)}])
                        above-threshold)
                      merged)]
       (update-execution-results! execution-id :merged-results filtered)
       filtered)))

#?(:clj
   (defn execute-retrieve-chunks
     "Retrieve full chunk documents by ID.
      Accepts optional opts map with :tenant and :environment for config resolution."
     ([execution-id docs-collection chunks-collection merged-results]
      (execute-retrieve-chunks execution-id docs-collection chunks-collection merged-results nil))
     ([execution-id docs-collection chunks-collection merged-results opts]
      (update-execution! execution-id {:stage :retrieve})
      (let [chunks (rag/retrieve-chunks-by-id
                    docs-collection
                    chunks-collection
                    merged-results
                    opts)]
        (update-execution-results! execution-id :retrieved-chunks chunks)
        chunks))))

#?(:clj
   (defn execute-rerank
     "Rerank chunks using ColBERT"
     [execution-id retrieved-chunks params]
     (update-execution! execution-id {:stage :rerank})
     ;; Log ColBERT configuration
     (let [colbert-url (cfg/get :services :colbert :api-url)
           colbert-key (cfg/get :services :colbert :api-key)]
       (t/log! :info [:playground/rerank-config
                      {:colbert-url colbert-url
                       :colbert-key-present? (some? colbert-key)
                       :colbert-key-length (when colbert-key (count colbert-key))
                       :chunks-to-rerank (count retrieved-chunks)}]))
     (try
       (let [{:keys [used-chunks used-docs full-prompt]} (rag/rerank-chunks retrieved-chunks params)]
         (t/log! :info [:playground/rerank-success
                        {:reranked-count (count used-chunks)}])
         (update-execution-results! execution-id :reranked-results used-chunks)
         (update-execution-results! execution-id :full-prompt full-prompt)
         {:used-chunks used-chunks
          :used-docs used-docs
          :full-prompt full-prompt})
       (catch Exception e
         (t/log! :error [:playground/rerank-error
                         {:error-message (.getMessage e)
                          :error-type (type e)}])
         (throw e)))))

#?(:clj
   (defn stream-playground-generation
     "Execute streaming LLM generation"
     [execution-id full-prompt config]
     (update-execution! execution-id {:stage :generate})
     (let [use-azure? (llm/use-azure-openai)
           azure-deployment (cfg/get :services :azure-openai :deployment-name)
           ;; For Azure, always use the deployment name; for OpenAI, use config or default
           model (if use-azure?
                   azure-deployment
                   (or (:model config) "gpt-4o"))
           system-prompt (rag/system-prompt-with-date)
           azure-endpoint (cfg/get :services :azure-openai :api-endpoint)
           azure-key (cfg/get :services :azure-openai :api-key)]
       (t/log! :info [:playground/llm-config
                      {:use-azure? use-azure?
                       :model model
                       :azure-deployment azure-deployment
                       :azure-endpoint azure-endpoint
                       :azure-key-present? (some? azure-key)
                       :prompt-length (count full-prompt)}])
       (if use-azure?
         ;; Azure OpenAI (non-streaming - streaming not well supported with Azure)
         (try
           (t/log! :info [:playground/llm-starting {:execution-id execution-id}])
           (let [response (openai/create-chat-completion
                           {:model model
                            :messages [{:role "system" :content system-prompt}
                                       {:role "user" :content full-prompt}]
                            :temperature (or (:temperature config) 0.1)}
                           {:api-key azure-key
                            :api-endpoint azure-endpoint
                            :impl :azure})
                 content (get-in response [:choices 0 :message :content])]
             (t/log! :info [:playground/llm-complete
                            {:execution-id execution-id
                             :content-length (count content)}])
             (append-streaming-content! execution-id content)
             content)
           (catch Exception e
             (t/log! :error [:playground/llm-error
                             {:execution-id execution-id
                              :error-message (.getMessage e)
                              :error-type (str (type e))}])
             (throw e)))
         ;; Non-streaming fallback for other providers
         (let [response (openai/create-chat-completion
                         {:model model
                          :messages [{:role "system" :content system-prompt}
                                     {:role "user" :content full-prompt}]
                          :temperature (or (:temperature config) 0.1)
                          :max_tokens (:max-tokens config)})
               content (get-in response [:choices 0 :message :content])]
           (append-streaming-content! execution-id content)
           content)))))

#?(:clj
   (defn execute-playground-pipeline
     "Execute the full playground pipeline, storing results at each stage.
      Returns the execution-id for tracking."
     [{:keys [entity-id query config query-relax-prompt rag-generate-prompt filter-by]}]
     (let [execution-id (nano-id)
           entity (get-entity-by-id entity-id)
           _ (when-not entity
               (throw (ex-info (str "Entity not found: " entity-id) {:entity-id entity-id})))

           ;; Initialize execution state
           _ (swap! !playground-executions assoc execution-id
                    {:status :running
                     :stage :init
                     :streaming-content ""
                     :results {}
                     :error nil
                     :started-at (str (java.time.Instant/now))
                     :entity-id entity-id
                     :query query})

           ;; Build params for reranking (similar to api-rag-handler)
           ;; Uses 3-level precedence: playground config > entity config > global default
           rag-params {:conversation-id execution-id
                       :entity-id entity-id
                       :original_user_query query
                       :translated_user_query query
                       :selected-model (:model config)
                       ;; Rerank parameters
                       :rerankTopkChunks (or (:rerank-top-k config)
                                             (:rerank-top-k entity))
                       :rerankMaxChunkLength (or (:rerank-max-chunk-length config)
                                                 (:rerank-max-chunk-length entity))
                       :rerankMaxLength (or (:rerank-max-total-length config)
                                            (:rerank-max-total-length entity))
                       ;; Context parameters
                       :contextTopkChunks (or (:context-top-k config)
                                              (:context-top-k entity))
                       :contextMaxChunkLength (or (:context-max-chunk-length config)
                                                  (:context-max-chunk-length entity))
                       :maxContextLength (or (:context-max-total-length config)
                                             (:context-max-total-length entity))
                       ;; Collection names
                       :docsCollectionName (:docs-collection entity)
                       :chunksCollectionName (:chunks-collection entity)
                       :phrasesCollectionName (:phrases-collection entity)
                       ;; Prompts (use new kebab-case property names)
                       :promptRagQueryRelax (or query-relax-prompt (:prompt-query-relax entity))
                       :promptRagGenerate (or rag-generate-prompt (:prompt-rag-generate entity))}]

       ;; Execute pipeline in a future to not block
       (future
         (try
           ;; Stage 1: Query relaxation
           (let [search-phrases (execute-query-relaxation
                                 execution-id
                                 query
                                 (:promptRagQueryRelax rag-params))]

             ;; Stage 2: Run all three searches
             (let [phrase-results (execute-phrase-search
                                   execution-id
                                   (:phrasesCollectionName rag-params)
                                   (:docsCollectionName rag-params)
                                   search-phrases
                                   (:phrase-gen-prompt entity)
                                   filter-by)
                   metadata-results (execute-metadata-search
                                     execution-id
                                     (:chunksCollectionName rag-params)
                                     (:docsCollectionName rag-params)
                                     search-phrases
                                     filter-by)
                   content-results (execute-content-search
                                    execution-id
                                    (:chunksCollectionName rag-params)
                                    (:docsCollectionName rag-params)
                                    search-phrases
                                    filter-by)]

               ;; Stage 3: Merge results (with optional threshold filtering)
               (let [merged (execute-merge-results
                             execution-id
                             phrase-results
                             metadata-results
                             content-results
                             (:rerank-threshold config))]

                 ;; Stage 4: Retrieve chunks
                 (let [retrieved (execute-retrieve-chunks
                                  execution-id
                                  (:docsCollectionName rag-params)
                                  (:chunksCollectionName rag-params)
                                  merged)]

                   ;; Stage 5: Rerank
                   (let [{:keys [used-chunks full-prompt]} (execute-rerank
                                                            execution-id
                                                            retrieved
                                                            rag-params)]

                     ;; Stage 6: Generate response
                     (stream-playground-generation execution-id full-prompt config)

                     ;; Store used chunks
                     (update-execution-results! execution-id :used-chunks used-chunks)

                     ;; Mark complete
                     (update-execution! execution-id
                                        {:status :complete
                                         :stage :complete
                                         :completed-at (str (java.time.Instant/now))}))))))
           (catch Exception e
             (println "Playground execution error:" (.getMessage e))
             (update-execution! execution-id
                                {:status :error
                                 :error (.getMessage e)
                                 :completed-at (str (java.time.Instant/now))}))))

       ;; Return execution ID immediately
       execution-id)))

#?(:clj
   (defn get-execution
     "Get execution state by ID"
     [execution-id]
     (get @!playground-executions execution-id)))

#?(:clj
   (defn cleanup-old-executions!
     "Remove executions older than the specified duration (in minutes)"
     [max-age-minutes]
     (let [cutoff (-> (java.time.Instant/now)
                      (.minusSeconds (* max-age-minutes 60)))]
       (swap! !playground-executions
              (fn [execs]
                (into {}
                      (filter (fn [[_ v]]
                                (or (nil? (:started-at v))
                                    (let [started (java.time.Instant/parse (:started-at v))]
                                      (.isAfter started cutoff))))
                              execs)))))))

;; =========Multi-Message Chat Pipeline=========

#?(:clj
   (defn messages->context
     "Convert persisted messages to format expected by query-relaxation.
      Filters to only user and assistant messages with text content."
     [messages]
     (->> messages
          (filter #(#{:user :assistant} (:message/role %)))
          (filter #(not (str/blank? (:message/text %))))
          (mapv #(select-keys % [:message/role :message/text])))))

#?(:clj
   (defn execute-playground-chat-pipeline
     "Execute the playground pipeline with conversation history and persistence.
      Supports multi-turn context and branching.

      Parameters:
        :conversation-id - ID of the conversation (creates new if nil)
        :tenant - Explicit tenant for config resolution (optional, uses env var if nil)
        :environment - Explicit environment for config resolution (optional, uses env var if nil)
        :entity-id - Entity configuration ID
        :query - Current user query
        :config - Pipeline configuration (model, temperature, etc.)
        :parent-msg-id - Parent message ID for branching (nil for continuation)
        :branch-index - Branch index when creating alternative branches
        :query-relax-prompt - Optional custom query relaxation prompt
        :rag-generate-prompt - Optional custom RAG generation prompt
        :filter-by - Optional Typesense filter
        :user-id - User ID for conversation ownership

      Returns the execution-id for tracking."
     [{:keys [conversation-id tenant environment entity-id query config parent-msg-id branch-index
              query-relax-prompt rag-generate-prompt filter-by user-id]}]
     (let [execution-id (nano-id)
           conn (db/get-conn)
           ;; Use explicit tenant/environment if provided, otherwise fall back to env vars
           effective-tenant (or tenant (digdir.config.core/get-tenant))
           effective-env (or environment (digdir.config.core/get-environment))
           ;; Get entity with explicit scope
           entity (when entity-id
                    (when-let [config-conn (digdir.config.db/get-conn)]
                      (digdir.config.db/get-entity
                       @config-conn
                       effective-tenant
                       effective-env
                       entity-id
                       (digdir.config.core/get-master-key))))
           _ (when-not entity
               (throw (ex-info (str "Entity not found: " entity-id) {:entity-id entity-id
                                                                      :tenant effective-tenant
                                                                      :environment effective-env})))

           ;; Create conversation if not provided
           actual-convo-id (or conversation-id
                               (:conversation-id
                                (db/create-playground-conversation conn entity-id user-id)))

           ;; Get message lineage for context (if we have a parent)
           context-messages (when parent-msg-id
                              (db/get-message-lineage @conn parent-msg-id))

           ;; Build full message history for query relaxation
           all-messages (-> (messages->context (or context-messages []))
                            (conj {:message/role :user
                                   :message/text query}))

           ;; Initialize execution state
           _ (swap! !playground-executions assoc execution-id
                    {:status :running
                     :stage :init
                     :streaming-content ""
                     :results {}
                     :error nil
                     :started-at (str (java.time.Instant/now))
                     :tenant effective-tenant
                     :environment effective-env
                     :entity-id entity-id
                     :conversation-id actual-convo-id
                     :query query
                     :parent-msg-id parent-msg-id
                     :branch-index branch-index})

           ;; Build params for reranking
           ;; Uses 3-level precedence: playground config > entity config > global default
           rag-params {:conversation-id actual-convo-id
                       :execution-id execution-id
                       :tenant effective-tenant
                       :environment effective-env
                       :entity-id entity-id
                       :original_user_query query
                       :translated_user_query query
                       :selected-model (:model config)
                       ;; Rerank parameters
                       :rerankTopkChunks (or (:rerank-top-k config)
                                             (:rerank-top-k entity))
                       :rerankMaxChunkLength (or (:rerank-max-chunk-length config)
                                                 (:rerank-max-chunk-length entity))
                       :rerankMaxLength (or (:rerank-max-total-length config)
                                            (:rerank-max-total-length entity))
                       ;; Context parameters
                       :contextTopkChunks (or (:context-top-k config)
                                              (:context-top-k entity))
                       :contextMaxChunkLength (or (:context-max-chunk-length config)
                                                  (:context-max-chunk-length entity))
                       :maxContextLength (or (:context-max-total-length config)
                                             (:context-max-total-length entity))
                       ;; Collection names
                       :docsCollectionName (:docs-collection entity)
                       :chunksCollectionName (:chunks-collection entity)
                       :phrasesCollectionName (:phrases-collection entity)
                       ;; Prompts (use new kebab-case property names)
                       :promptRagQueryRelax (or query-relax-prompt (:prompt-query-relax entity))
                       :promptRagGenerate (or rag-generate-prompt (:prompt-rag-generate entity))}

           ;; Build Typesense opts for config resolution with explicit tenant/environment
           ts-opts {:tenant effective-tenant :environment effective-env}]

       ;; Execute pipeline in a future to not block
       (future
         (try
           ;; Stage 1: Query relaxation with full history
           (let [search-phrases (execute-query-relaxation-with-history
                                 execution-id
                                 all-messages
                                 (:promptRagQueryRelax rag-params))]

             ;; Stage 2: Run all three searches (pass ts-opts for config resolution)
             (let [phrase-results (execute-phrase-search
                                   execution-id
                                   (:phrasesCollectionName rag-params)
                                   (:docsCollectionName rag-params)
                                   search-phrases
                                   (:phrase-gen-prompt entity)
                                   filter-by
                                   ts-opts)
                   metadata-results (execute-metadata-search
                                     execution-id
                                     (:chunksCollectionName rag-params)
                                     (:docsCollectionName rag-params)
                                     search-phrases
                                     filter-by
                                     ts-opts)
                   content-results (execute-content-search
                                    execution-id
                                    (:chunksCollectionName rag-params)
                                    (:docsCollectionName rag-params)
                                    search-phrases
                                    filter-by
                                    ts-opts)]

               ;; Stage 3: Merge results (with optional threshold filtering)
               (let [merged (execute-merge-results
                             execution-id
                             phrase-results
                             metadata-results
                             content-results
                             (:rerank-threshold config))]

                 ;; Stage 4: Retrieve chunks
                 (let [retrieved (execute-retrieve-chunks
                                  execution-id
                                  (:docsCollectionName rag-params)
                                  (:chunksCollectionName rag-params)
                                  merged
                                  ts-opts)]

                   ;; Stage 5: Rerank
                   (let [{:keys [used-chunks full-prompt]} (execute-rerank
                                                            execution-id
                                                            retrieved
                                                            rag-params)]

                     ;; Stage 6: Generate response
                     (stream-playground-generation execution-id full-prompt config)

                     ;; Store used chunks in execution state
                     (update-execution-results! execution-id :used-chunks used-chunks)

                     ;; === PERSISTENCE ===
                     ;; Now persist the user message and assistant response to Datahike
                     (let [response-text (get-in @!playground-executions
                                                  [execution-id :streaming-content])
                           ;; Build lookup for title/metadata from retrieved chunks
                           docs-coll-key (keyword (:docsCollectionName rag-params))
                           chunk-lookup (into {}
                                              (map (fn [c]
                                                     [(:chunk_id c)
                                                      {:title (get-in c [docs-coll-key :title])
                                                       :metadata (:metadata c)}])
                                                   retrieved))
                           ;; Enrich result with title/metadata from lookup
                           enrich-result (fn [r]
                                           (let [chunk-id (:chunk_id r)
                                                 lookup-data (get chunk-lookup chunk-id)]
                                             (-> (select-keys r [:chunk_id :rank :search-types :hit-count])
                                                 (assoc :title (:title lookup-data))
                                                 (assoc :metadata (or (:metadata lookup-data)
                                                                      (:metadata r))))))
                           diagnostics {:query-relaxation search-phrases
                                        :phrase-search-count (count phrase-results)
                                        :phrase-search (mapv enrich-result (take 20 phrase-results))
                                        :metadata-search-count (count metadata-results)
                                        :metadata-search (mapv enrich-result (take 20 metadata-results))
                                        :content-search-count (count content-results)
                                        :content-search (mapv enrich-result (take 20 content-results))
                                        :merged-count (count merged)
                                        :merged-results (mapv enrich-result (take 20 merged))
                                        :used-chunks-count (count used-chunks)
                                        :used-chunks (mapv enrich-result used-chunks)}

                           ;; Persist user message
                           user-msg-result (db/transact-playground-user-msg
                                            conn
                                            actual-convo-id
                                            query
                                            config
                                            parent-msg-id
                                            branch-index)
                           user-msg-id (:message/id user-msg-result)

                           ;; Persist assistant response (parent is the user message)
                           assistant-msg-result (db/transact-playground-assistant-msg
                                                 conn
                                                 actual-convo-id
                                                 response-text
                                                 diagnostics
                                                 execution-id
                                                 user-msg-id
                                                 0)]  ; Assistant always branch-index 0 under its parent

                       ;; Store message IDs in execution state
                       (update-execution! execution-id
                                          {:user-msg-id user-msg-id
                                           :assistant-msg-id (:message/id assistant-msg-result)}))

                     ;; Mark complete
                     (update-execution! execution-id
                                        {:status :complete
                                         :stage :complete
                                         :completed-at (str (java.time.Instant/now))}))))))
           (catch Exception e
             (t/log! :error [:playground-chat/pipeline-error
                             {:execution-id execution-id
                              :error-message (.getMessage e)
                              :error-type (str (type e))}])
             ;; Gather Typesense diagnostics on error
             (let [expected-collections [(:docs-collection entity)
                                         (:chunks-collection entity)
                                         (:phrases-collection entity)]
                   ts-diagnostics (try
                                    (get-typesense-diagnostics expected-collections ts-opts)
                                    (catch Exception diag-e
                                      {:diagnostic-error (.getMessage diag-e)}))]
               (update-execution! execution-id
                                  {:status :error
                                   :error (.getMessage e)
                                   :error-type (str (type e))
                                   :typesense-diagnostics ts-diagnostics
                                   :completed-at (str (java.time.Instant/now))})))))

       ;; Return execution ID and conversation ID immediately
       {:execution-id execution-id
        :conversation-id actual-convo-id})))
