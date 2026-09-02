(ns digdir.playground.core
  "Backend logic for the RAG Playground feature."
  (:require #?(:clj [digdir.rag.typesense :as ts-utils])
            #?(:clj [typesense.client :as ts-client])
            #?(:clj [digdir.api.util :as api-util])
            #?(:clj [digdir.config.accessor :as cfg])
            #?(:clj [digdir.agents.db :as agents-db])
            #?(:clj [digdir.api.routes.endpoints.debug :as debug])
            #?(:clj [digdir.config.core])
            #?(:clj [digdir.config.db])
            #?(:clj [digdir.data.db :as db])
            #?(:clj [digdir.llm.openai :as llm])
            #?(:clj [nano-id.core :refer [nano-id]])
            #?(:clj [taoensso.telemere :as t])
            #?(:clj [digdir.skills.api :as skills-api])
            #?(:clj [digdir.skills.events :as skill-events])
            #?(:clj [digdir.skills.invoke :as skills-invoke])
            #?(:clj [digdir.playground.action-trace :as action-trace])
            #?(:clj [digdir.playground.diagnostics :as pg-diagnostics])
            #?(:clj [digdir.playground.timeline :as pg-timeline])
            [clojure.string]))

;; Execution state atom - stores results from each pipeline stage
#?(:clj (defonce !playground-executions (atom {})))

;; Current execution ID - tracked on server for reactive UI updates
#?(:clj (defonce !current-execution-id (atom nil)))

#?(:clj
   (defonce ^:private !live-status-schedule-fn
     (delay (do (require 'digdir.playground.live-status-scheduler)
                (resolve 'digdir.playground.live-status-scheduler/schedule-live-status-summary!)))))

#?(:clj
   (defn fetch-chunk-by-id
     "Fetch full chunk data from Typesense by chunk_id.
      Accepts optional opts map with :tenant and :dataset-config-key for config resolution."
     ([chunks-collection docs-collection chunk-id]
      (fetch-chunk-by-id chunks-collection docs-collection chunk-id nil))
     ([chunks-collection docs-collection chunk-id opts]
      (when (and chunks-collection chunk-id)
        (try
          (let [search-args {:searches [{:collection chunks-collection
                                         :q chunk-id
                                         :query_by "chunk_id"
                                         :filter_by (str "chunk_id:=`" chunk-id "`")
                                         :include_fields (str "id,chunk_id,doc_num,chunk_index,content_markdown,content_length,metadata,$"
                                                              docs-collection "(url,title,total_chunks,orgs_long,orgs_short)")
                                         :per_page 1}]}
                response (ts-client/multi-search (ts-utils/make-ts-settings opts) search-args {:query_by "chunk_id"})
                hits (get-in response [:results 0 :hits])]
            (when (seq hits)
              (:document (first hits))))
          (catch Exception e
            (t/log! :warn [:playground/fetch-chunk-by-id-failed
                           {:chunk-id chunk-id
                            :chunks-collection chunks-collection
                            :docs-collection docs-collection
                            :error (.getMessage e)}])
            nil))))))

#?(:clj
   (defn fetch-dataset-stats
     "Return lightweight Typesense collection counts for a selected dataset.

      Missing or unreachable collections are reported as nil so the Playground
      empty state can remain useful without turning metadata into a chat blocker."
     [dataset-config opts]
     (let [collection-count
           (fn [collection-name]
             (when-not (clojure.string/blank? collection-name)
               (try
                 (:num_documents
                  (ts-client/retrieve-collection
                   (ts-utils/make-ts-settings opts)
                   collection-name))
                 (catch Exception e
                   (t/log! :warn [:playground/fetch-dataset-stats-failed
                                  {:collection collection-name
                                   :error (.getMessage e)}])
                   nil))))]
       {:documents (collection-count (:docs-collection dataset-config))
        :chunks (collection-count (:chunks-collection dataset-config))
        :phrases (collection-count (:phrases-collection dataset-config))})))

;; =========Typesense Diagnostics=========

#?(:clj
   (defn list-typesense-collections
     "List all available Typesense collections.
      Accepts optional opts map with :tenant and :dataset-config-key for config resolution."
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
      Accepts optional opts map with :tenant and :dataset-config-key for config resolution."
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
      Accepts optional opts map with :tenant and :dataset-config-key for config resolution."
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
   (defn emit-execution-event!
     "Append a normalized execution event to an execution's in-memory event stream."
     [execution-id event]
     (swap! !playground-executions update-in [execution-id :events]
            (fnil conj [])
            (merge {:ts (System/currentTimeMillis)
                    :execution-id execution-id
                    :request-id execution-id}
                   (skill-events/normalize-execution-event event)))
     (when-let [schedule-fn @!live-status-schedule-fn]
       (schedule-fn execution-id))))

#?(:clj
   (defn set-execution-stage!
     "Set execution stage and emit a stage/started event."
     ([execution-id stage]
      (set-execution-stage! execution-id stage nil))
     ([execution-id stage label]
      (update-execution! execution-id {:stage stage})
      (emit-execution-event! execution-id
                             (skill-events/stage-started stage
                                                         (or label (name stage)))))))

#?(:clj
   (defn update-execution-results!
     "Update specific results in execution state"
     [execution-id result-key value]
     (swap! !playground-executions assoc-in [execution-id :results result-key] value)))

#?(:clj
   (defn get-execution
     "Get execution state by ID"
     [execution-id]
     (get @!playground-executions execution-id)))

#?(:clj
   (defn get-latest-execution-id-for-conversation
     "Find the most recent execution-id for a conversation"
     [conversation-id]
     (when conversation-id
       (->> @!playground-executions
            (filter (fn [[_ v]] (= (:conversation-id v) conversation-id)))
            (sort-by (fn [[_ v]] (:started-at v)))
            last
            first))))

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
         "Convert persisted messages to format expected by query-planner.
      Filters to only user and assistant messages with text content."
     [messages]
     (->> messages
          (filter #(#{:user :assistant} (:message/role %)))
          (filter #(not (clojure.string/blank? (:message/text %))))
          (mapv (fn [m]
                  {:role (:message/role m)
                   :text (:message/text m)
                   :message/role (:message/role m)
                   :message/text (:message/text m)})))))

#?(:clj
   (defn- split-chunks-by-search-type
     "Split chunks into per-search-type lists based on :search-types metadata."
     [chunks]
     {:phrase (filterv #(contains? (:search-types %) :phrase) chunks)
      :metadata (filterv #(contains? (:search-types %) :metadata) chunks)
      :content (filterv #(contains? (:search-types %) :content) chunks)}))

#?(:clj
   (defn- aggregate-search-attributions
     "Aggregate multiple retrieval attributions into one summary map."
     [attributions]
     (let [entries (filter map? (or attributions []))]
       (when (seq entries)
         (let [sum-key (fn [k] (reduce + 0 (map #(long (or (get % k) 0)) entries)))
               first-match (fn [k]
                             (some #(when (some? (get % k)) (get % k)) entries))]
           {:phrase (sum-key :phrase)
            :metadata (sum-key :metadata)
            :content (sum-key :content)
            :merged (sum-key :merged)
            :chunks-before-diversity (sum-key :chunks-before-diversity)
            :chunks-after-diversity (sum-key :chunks-after-diversity)
            :auto-filter-applied (first-match :auto-filter-applied)
            :auto-filter-fallback (boolean (some :auto-filter-fallback entries))})))))

#?(:clj
   (defn- normalize-rag-params
     "Normalize mixed naming conventions to one internal kebab-case map."
     [rag-params]
     (let [kebab (into {}
                       (map (fn [[k v]]
                              [(-> (name k)
                                   (clojure.string/replace "_" "-")
                                   (clojure.string/replace #"([a-z0-9])([A-Z])" "$1-$2")
                                   clojure.string/lower-case
                                   keyword)
                               v]))
                       (or rag-params {}))
           aliases {:docs-collection-name :docs-collection
                    :chunks-collection-name :chunks-collection
                    :phrases-collection-name :phrases-collection
                    :prompt-rag-query-relax :prompt-query-relax
                    :retrieve-topk-chunks :retrieve-top-k
                    :context-topk-chunks :context-top-k
                    :rerank-topk-chunks :rerank-top-k}]
       (reduce-kv (fn [acc k v]
                    (assoc acc (get aliases k k) v))
                  {}
                  kebab))))

#?(:clj
   (defn- typesense-diagnostics->backend-issues
     "Convert startup Typesense diagnostics into structured backend issues."
     [typesense-diagnostics]
     (let [diag (or typesense-diagnostics {})
           missing-collections (vec (or (:missing-collections diag) []))
           expected-collections (vec (or (:expected-collections diag) []))
           available-collections (vec (or (:available-collections diag) []))]
       (vec
        (concat
         (when-let [diagnostic-error (:diagnostic-error diag)]
           [{:source :typesense
             :tool "startup-check"
             :issue-type :typesense-diagnostics-failed
             :message diagnostic-error
             :details {:expected-collections expected-collections}}])
         (when (false? (:connected diag))
           [{:source :typesense
             :tool "startup-check"
             :issue-type :typesense-unreachable
             :message (or (:error diag) "Typesense connection failed")
             :details {:uri (:uri diag)
                       :expected-collections expected-collections}}])
         (when (seq missing-collections)
           [{:source :typesense
             :tool "startup-check"
             :issue-type :missing-collections
             :message "Expected Typesense collections are missing"
             :details {:missing-collections missing-collections
                       :expected-collections expected-collections
                       :available-collections available-collections}}]))))))

#?(:clj
   (defn- playground-config->api-params
     "Translate playground UI config keys to the shape api.util/build-rag-skill-params
      expects as `params` (HTTP body overrides). Most keys pass through; the playground
      UI uses :context-max-total-length where the API uses :max-context-length."
     [config]
     (cond-> {}
       (contains? config :rerank-top-k) (assoc :rerank-top-k (:rerank-top-k config))
       (contains? config :rerank-max-chunk-length) (assoc :rerank-max-chunk-length (:rerank-max-chunk-length config))
       (contains? config :rerank-max-total-length) (assoc :rerank-max-total-length (:rerank-max-total-length config))
       (contains? config :context-top-k) (assoc :context-top-k (:context-top-k config))
       (contains? config :context-min-chunks) (assoc :context-min-chunks (:context-min-chunks config))
       (contains? config :context-relative-score-threshold) (assoc :context-relative-score-threshold (:context-relative-score-threshold config))
       (contains? config :context-max-chunk-length) (assoc :context-max-chunk-length (:context-max-chunk-length config))
       (contains? config :context-max-total-length) (assoc :max-context-length (:context-max-total-length config)))))

#?(:clj
   (defn build-playground-skill-params
     "Build the nested :skill-params map for the playground.

      Delegates to api.util/build-rag-skill-params so the API and playground produce
      identical skill-params shapes — preventing the kind of silent divergence where
      e.g. the playground's rerank skill fell back to literal defaults (1000/10000/etc.)
      while the API delivered the configured :rerank-rag-* values.

      `agent-skill-params` is the resolved agent's :skill-params field (a map
      like `{:builtin/retrieval {:strategy-weights ...}}`). It slots between
      the dataset-config defaults and the per-call UI overrides — agent
      settings beat the dataset, UI controls still beat the agent. Pass `{}`
      (or omit) to skip the agent layer."
     ([dataset-config ui-config query-relax-prompt rag-generate-prompt]
      (build-playground-skill-params dataset-config ui-config
                                     query-relax-prompt rag-generate-prompt
                                     {}))
     ([dataset-config ui-config query-relax-prompt rag-generate-prompt agent-skill-params]
      (let [shaped-config (cond-> (or dataset-config {})
                            query-relax-prompt (assoc :query-planner-prompt query-relax-prompt)
                            rag-generate-prompt (assoc :synthesis-generation-prompt rag-generate-prompt))]
        (api-util/build-rag-skill-params shaped-config
                                         (playground-config->api-params (or ui-config {}))
                                         (or agent-skill-params {}))))))

#?(:clj
   (defn execute-skill-graph
     "Execute the playground skill graph.

      Parameters:
        execution-id - Execution ID for tracking
        query - User query
        all-messages - Conversation history
        rag-params - RAG configuration parameters
        config - Playground config (model, temperature, etc.)
        ts-opts - TypeSense options

     Returns: Map with :response and :diagnostics"
     [execution-id query all-messages rag-params config ts-opts]
     (t/log! :info [:skill-graph/starting {:execution-id execution-id}])

     ;; Update stage
     (set-execution-stage! execution-id :skills-init "Initializing skills")

     (try
       ;; Initialize skills system (safe to call multiple times)
       (skills-api/initialize!)

       ;; Build inputs for the simple-qa skill graph
       (let [rag-params (normalize-rag-params rag-params)
             collections {:docs-collection (:docs-collection rag-params)
                          :chunks-collection (:chunks-collection rag-params)
                          :phrases-collection (:phrases-collection rag-params)}
             expected-collections (vec (remove nil? [(:docs-collection collections)
                                                     (:chunks-collection collections)
                                                     (:phrases-collection collections)]))
             typesense-diagnostics (try
                                     (get-typesense-diagnostics expected-collections ts-opts)
                                     (catch Exception diag-e
                                       {:diagnostic-error (.getMessage diag-e)
                                        :expected-collections expected-collections}))
             startup-backend-issues (typesense-diagnostics->backend-issues typesense-diagnostics)
             _ (update-execution-results! execution-id :typesense-startup-diagnostics typesense-diagnostics)
             _ (when (seq startup-backend-issues)
                 (update-execution-results! execution-id :backend-issues startup-backend-issues)
                 (t/log! :warn [:skill-graph/typesense-startup-issues
                                {:execution-id execution-id
                                 :issues startup-backend-issues}]))
             progress-fn (fn [progress]
                           (doseq [execution-event (skill-events/progress->execution-events progress)]
                             (emit-execution-event! execution-id execution-event))
                           ;; Graph steps enter one canonical action trace. The
                           ;; same records feed the live view and are persisted
                           ;; with the completed diagnostics below.
                           (when-let [action (action-trace/progress->action progress)]
                             (swap! !playground-executions
                                    update-in [execution-id :action-trace]
                                    action-trace/upsert-action
                                    action))
                           (case (:event progress)
                             :agent/thinking
                             (swap! !playground-executions
                                    update execution-id
                                    (fnil (fn [execution]
                                            (assoc execution :live-thinking
                                                   {:iteration (:iteration progress)
                                                    :reasoning (:reasoning progress)}))
                                          {}))

                             :agent/turn-completed
                             ;; Note: we intentionally do NOT clear :live-thinking here.
                             ;; It stays sticky until the next :agent/thinking event
                             ;; overwrites it, so users keep seeing the most recent
                             ;; reasoning even during turns that produce no new thinking
                             ;; text. The panel stops rendering once :waiting? flips
                             ;; to false at the end of the execution.
                             (swap! !playground-executions
                                    update execution-id
                                    (fnil (fn [execution]
                                            (-> execution
                                                (update :live-agent-trace
                                                        (fnil conj [])
                                                        {:iteration (:iteration progress)
                                                         :reasoning (:reasoning progress)
                                                         :tool-calls (:tool-calls progress)})
                                                (update :live-agent-stage-timings
                                                        (fnil into [])
                                                        (vec (or (:stage-timings progress) [])))))
                                          {}))

                             nil))
             ;; Pass :model only when the user actively picked one. Leaving
             ;; it unset lets the synthesis skill honor its runtime config
             ;; (:synthesis-model) — execution-overrides in resolve-step-
             ;; parameters runs LAST in the merge order, so an unconditional
             ;; opts.model would shadow the per-skill value.
             opts (cond-> {:tenant (:tenant rag-params)
                           :dataset-config-key (:dataset-config-key rag-params)
                           :temperature (or (:temperature config) 0.1)
                           :conversation-history (messages->context all-messages)
                           :progress-fn progress-fn
                           :skill-params (:skill-params rag-params)}
                    (:user-model rag-params) (assoc :model (:user-model rag-params))
                    ;; assoc-execution-scope inside `run-skill-graph` lifts
                    ;; this into skill-params so the agent skill's
                    ;; ambient-ctx :agent-id is populated.
                    (:agent-id rag-params) (assoc :agent-id (:agent-id rag-params)))
             ;; E2E scaffold: record the resolved skill-params for the
             ;; Layer-C Playwright tests. No-op when capture is not
             ;; explicitly enabled, so production traffic doesn't pay
             ;; the atom-swap cost.
             _ (debug/record-last-invocation!
                 (:agent-id rag-params)
                 {:user-query query
                  :skill-params (:skill-params rag-params)
                  :skill-graph-id (:skill-graph config)
                  :source :playground})]

         ;; Update stage: query planning
         (set-execution-stage! execution-id :skills-query-planning "Planning queries")

         ;; Execute the selected skill graph via the canonical invoke-rag entry
         ;; point. Playground keeps the rich, UI-coupled diagnostics shaping
         ;; below; the core call + output-fallback semantics live in
         ;; digdir.skills.invoke so the MCP server gets the same behavior.
         (let [skill-graph-id (keyword (or (:skill-graph config) "builtin/agent-rag-graph-bundled"))
               _ (t/log! :info [:skill-graph/executing-skill-graph
                                {:skill-graph skill-graph-id
                                 :query query
                                 :collections collections}])
               invoke-result (skills-invoke/invoke-rag
                              {:user-query query
                               :claim query
                               :conversation-history (:conversation-history opts)
                               :collections collections
                               :skill-graph-id skill-graph-id
                               :skill-params (:skill-params opts)
                               :execution-scope {:tenant (:tenant opts)
                                                 :dataset-config-key (:dataset-config-key opts)
                                                 :agent-id (:agent-id opts)}
                               :model (:model opts)
                               :temperature (:temperature opts)
                               :progress-fn progress-fn})
               result (:raw-result invoke-result)
               agent-step-result (or (get-in result [:step-results :agent])
                                     (some (fn [[_ step-result]]
                                             (when (contains? (or (:outputs step-result) {}) :trace)
                                               step-result))
                                           (:step-results result)))
               _ (t/log! :debug [:skill-graph/raw-result
                                 {:result-type (type result)
                                  :result-keys (when (map? result) (keys result))
                                  :outputs-keys (when (map? result) (keys (:outputs result)))
                                  :search-attribution (get-in result [:outputs :search-attribution])}])]
           (if (= :error (:status invoke-result))
             ;; Handle error
             (let [error (:error invoke-result)]
               (t/log! :error [:skill-graph/failed {:error error}])
               (update-execution! execution-id
                                  {:status :error
                                   :error (:error-message error)
                                   :error-type (str (:error-type error))})
               (emit-execution-event! execution-id
                                      (skill-events/request-failed (:error-message error)))
               {:response nil
                :error error
                :diagnostics {:skills-error error}})

             ;; Handle success
             (let [outputs (get-in invoke-result [:diagnostics :outputs])
                   clarification-request (:clarification-request outputs)
                   response-status (if clarification-request
                                     :needs_clarification
                                     :complete)
                   backend-issues (vec (concat startup-backend-issues
                                               (or (:backend-issues outputs) [])))
                   response (:response invoke-result)
                   chunks (:chunks invoke-result)
                   queries (:queries invoke-result)
                   agent-trace (:trace outputs)
                   canonical-actions (action-trace/normalize-action-trace
                                      (get-in @!playground-executions
                                              [execution-id :action-trace]))
                   is-agentic (some? agent-trace)
                   agent-stage-timings (vec (or (get-in agent-step-result [:metadata :stage-timings]) []))
                   ;; Extract search attribution for UI diagnostics
                   search-attributions (or (:search-attributions outputs) [])
                   search-attribution (or (:search-attribution outputs)
                                          (aggregate-search-attributions search-attributions)
                                          {})
                   search-history (:search-history outputs)
                   latest-search-entry (last search-history)
                   agentic-merged-results (mapv (fn [summary]
                                                 {:chunk_id (:chunk-id summary)
                                                  :doc_num (:doc-num summary)
                                                  :chunk_index (:chunk-index summary)
                                                  :content_length (:content-length summary)
                                                  :total_chunks (:total-chunks summary)
                                                  :title (:title summary)
                                                  :metadata (:headers summary)
                                                  :search-types (:search-types summary)
                                                  :retrieval-boosts (:retrieval-boosts summary)})
                                               (or (:chunk-summaries latest-search-entry) []))
                   ;; Format chunks for diagnostics using the established Playground shape.
                   format-chunk (fn [c]
                                  (select-keys c [:chunk_id :doc_num :chunk_index :content_length
                                                  :total_chunks :rank :search-types :hit-count
                                                  :title :metadata :content_markdown :type-ranks
                                                  :retrieval-boosts]))
                   ;; For agentic graphs, extract search phrases from trace if not provided
                   effective-queries (if (and is-agentic (empty? queries))
                                       (let [trace-phrases (pg-timeline/extract-search-phrases-from-trace agent-trace)]
                                         (if (seq trace-phrases) trace-phrases [query]))
                                       (or queries [query]))
                   ;; For non-agentic graphs, split chunks by search type
                   chunks-by-type (when (and (not is-agentic) (seq chunks))
                                    (split-chunks-by-search-type chunks))
                   ;; Build per-type search results
                   agentic-by-type (when is-agentic (split-chunks-by-search-type agentic-merged-results))
                   phrase-results (cond
                                    chunks-by-type (mapv format-chunk (:phrase chunks-by-type))
                                    agentic-by-type (:phrase agentic-by-type)
                                    :else (vec (repeat (get search-attribution :phrase 0) {:search-type :phrase})))
                   metadata-results (cond
                                      chunks-by-type (mapv format-chunk (:metadata chunks-by-type))
                                      agentic-by-type (:metadata agentic-by-type)
                                      :else (vec (repeat (get search-attribution :metadata 0) {:search-type :metadata})))
                   content-results (cond
                                     chunks-by-type (mapv format-chunk (:content chunks-by-type))
                                     agentic-by-type (:content agentic-by-type)
                                     :else (vec (repeat (get search-attribution :content 0) {:search-type :content})))
                   merged-count (if is-agentic
                                  (count agentic-merged-results)
                                  (get search-attribution :merged (count chunks)))
                   retrieval-filters (pg-diagnostics/collect-retrieval-filters
                                       search-attribution
                                       search-attributions
                                       agent-trace)]

               (t/log! :info [:skill-graph/completed
                              {:execution-id execution-id
                               :response-length (count response)
                               :chunks-count (count chunks)
                               :search-attribution search-attribution
                               :is-agentic is-agentic}])

               ;; Store results in execution state for UI display
               (update-execution-results! execution-id :query-relaxation effective-queries)
               (update-execution-results! execution-id :phrase-search phrase-results)
               (update-execution-results! execution-id :metadata-search metadata-results)
               (update-execution-results! execution-id :content-search content-results)
               (update-execution-results! execution-id :merged-results (if is-agentic
                                                                         agentic-merged-results
                                                                         (mapv format-chunk (take 20 chunks))))
               (update-execution-results! execution-id :retrieved-chunks chunks)
               (update-execution-results! execution-id :used-chunks (mapv format-chunk chunks))
               (update-execution-results! execution-id :backend-issues backend-issues)

               ;; Stream the response to execution state
               (set-execution-stage! execution-id :skills-generating "Generating response")
               (update-execution! execution-id {:streaming-content response})
               (emit-execution-event! execution-id
                                      (skill-events/response-chunk response))
               (emit-execution-event! execution-id
                                      (skill-events/stage-completed :skills-generating
                                                                    "Generating response"))

               {:response response
                :chunks chunks
               :status response-status
               :clarification-request clarification-request
               :diagnostics {:status response-status
                              :clarification-request clarification-request
                              :query-relaxation effective-queries
                              :query-intent (:query-intent outputs)
                              :budget-state (:budget-state outputs)
                              :typesense-startup-diagnostics typesense-diagnostics
                              :search-history search-history
                              :read-history (:read-history outputs)
                              :search-errors (:search-errors outputs)
                              :backend-issues backend-issues
                              :sufficiency-decisions (:sufficiency-decisions outputs)
                              :last-insufficiency (:last-insufficiency outputs)
                              :last-response-validation-insufficiency (:last-response-validation-insufficiency outputs)
                              ;; Search counts for UI display
                              :phrase-search-count (count phrase-results)
                              :metadata-search-count (count metadata-results)
                              :content-search-count (count content-results)
                              :merged-count merged-count
                              ;; Detailed results
                              :phrase-search phrase-results
                              :metadata-search metadata-results
                              :content-search content-results
                              :merged-results (if is-agentic
                                                agentic-merged-results
                                                (mapv format-chunk (take 20 chunks)))
                              :used-chunks-count (count chunks)
                              :used-chunks (mapv format-chunk chunks)
                              ;; Additional skill metadata (graph-level execution metadata)
                              :skill-execution-metadata (:execution-metadata result)
                              ;; Execution timing from graph runner
                              :execution-timing (get-in result [:execution-metadata :step-timings])
                              :execution-stage-timings (get-in result [:execution-metadata :stage-timings])
                              :agent-stage-timings agent-stage-timings
                              :total-duration-ms (get-in result [:execution-metadata :total-duration-ms])
                              ;; Agent trace (only present for agentic skill graphs)
                              :agent-trace agent-trace
                              :action-trace canonical-actions
                              ;; Graph-variant self-improve agents emit a
                              ;; structured stats map alongside the
                              ;; Markdown :report. Plumbed through so
                              ;; the side drawer can render kept /
                              ;; reverted counts as chips without
                              ;; re-parsing the report text.
                              :report-structured (:report-structured outputs)
                              :agent-trace-count (count agent-trace)
                              ;; Retrieval filters (applies to all skill graph types)
                              :retrieval-filters retrieval-filters
                              ;; Citation data. Agent-rag graphs stash these on
                              ;; the workspace via the generate_response tool
                              ;; (agent/tools.clj:1092), so they don't appear at
                              ;; the top of `outputs` — only inside
                              ;; `workspace-final`. Older/non-agent graphs
                              ;; surface them directly. Check both.
                              :citations (or (get-in outputs [:workspace-final :citations])
                                             (:citations outputs))
                              :citation-index (or (get-in outputs [:workspace-final :citation-index])
                                                  (:citation-index outputs))
                              ;; Auto-filter info
                              :auto-filter-applied (:auto-filter-applied search-attribution)
                              :auto-filter-fallback (:auto-filter-fallback search-attribution)}}))))

       (catch Exception e
         (t/log! :error [:skill-graph/exception
                         {:execution-id execution-id
                          :error (.getMessage e)
                          :ex-data (ex-data e)}])
         (emit-execution-event! execution-id
                                (skill-events/request-failed (.getMessage e)))
         {:response nil
          :error {:error-type :exception
                  :error-message (.getMessage e)}
          :diagnostics {:exception (.getMessage e)
                        :ex-data (ex-data e)
                        :action-trace (action-trace/normalize-action-trace
                                       (get-in @!playground-executions
                                               [execution-id :action-trace]))}}))))

#?(:clj
   (defn execute-playground-chat-pipeline
     "Execute the playground pipeline with conversation history and persistence.
      Supports multi-turn context and branching.

      Parameters:
        :conversation-id - ID of the conversation (creates new if nil)
        :tenant - Explicit tenant for config resolution (optional, uses env var if nil)
        :dataset-config-key - Explicit dataset-config-key for dataset resolution (optional, uses env var if nil)
        :query - Current user query
        :config - Playground execution overrides (model, temperature, etc.)
        :parent-msg-id - Parent message ID for branching (nil for continuation)
        :branch-index - Branch index when creating alternative branches
        :query-relax-prompt - Optional custom query relaxation prompt
        :rag-generate-prompt - Optional custom RAG generation prompt
        :user-id - User ID for conversation ownership

      Returns the execution-id for tracking."
     [{:keys [conversation-id tenant dataset-config-key runtime-config-key agent-id query config parent-msg-id branch-index
              query-relax-prompt rag-generate-prompt user-id]}]
     (let [execution-id     (nano-id)
           conn             (db/get-conn)
           effective-agent-id agent-id
           _ (when-not effective-agent-id
               (throw (ex-info "Missing required field: agent-id" {:status 400})))
           effective-tenant tenant
           effective-dataset-config-key dataset-config-key
           effective-runtime-config-key (or runtime-config-key "default")
           runtime-config-result
                           (try
                             (let [runtime-opts (cond-> {:tenant effective-tenant
                                                         :agent-id effective-agent-id
                                                         :tenant-config-key effective-runtime-config-key}
                                                  (:runtime-node-id config) (assoc :node-id (:runtime-node-id config))
                                                  (:runtime-config-key config) (assoc :tenant-config-key (:runtime-config-key config))
                                                  (:runtime-tenant-config-key config) (assoc :tenant-config-key (:runtime-tenant-config-key config))
                                                  (and (not (:runtime-node-id config))
                                                       (not (:runtime-config-key config))
                                                       (not (:runtime-tenant-config-key config)))
                                                  (assoc :tenant-config-key "default")
                                                  (:dataset-id config) (assoc :dataset-id (:dataset-id config)))]
                               (assoc (cfg/get-runtime-skill-config-v2-with-trace runtime-opts)
                                      :source :v2))
                             (catch Exception e
                               (throw (ex-info "Runtime config resolution failed"
                                               {:tenant effective-tenant
                                                :dataset-config-key effective-dataset-config-key
                                                :runtime-config-key effective-runtime-config-key
                                                :agent-id effective-agent-id
                                                :runtime-config-error {:message (.getMessage e)
                                                                       :type (str (type e))}}
                                               e))))
           ;; Get dataset config with explicit scope
           dataset-ref      {:tenant effective-tenant
                             :dataset-config-key effective-dataset-config-key}
           dataset-config   (when-let [config-conn (digdir.config.db/get-conn)]
                              (let [master-key (digdir.config.core/get-master-key)]
                                (some-> (digdir.config.db/get-dataset-by-ref
                                         @config-conn
                                         dataset-ref
                                         master-key)
                                        (merge (:config runtime-config-result)))))
           canonical-dataset-config-key
                           (or (:dataset-config-key dataset-config)
                               effective-dataset-config-key)
           _                (when-not dataset-config
                              (throw (ex-info "Dataset not found"
                                              {:dataset-ref dataset-ref
                                               :tenant effective-tenant
                                               :dataset-config-key effective-dataset-config-key})))

           ;; Create conversation if not provided
           actual-convo-id  (or conversation-id
                                (:conversation-id
                                 (db/create-playground-conversation conn effective-agent-id
                                   {:user-id user-id
                                    :tenant effective-tenant
                                    :dataset-config-key canonical-dataset-config-key
                                    :skill-graph-id (or (:skill-graph config) "builtin/agent-rag-graph-bundled")})))

           ;; Persist user message IMMEDIATELY so it appears in UI before pipeline runs
           user-msg-result  (db/transact-playground-user-msg
                              conn actual-convo-id query config parent-msg-id branch-index)
           user-msg-id      (:message/id user-msg-result)

           ;; Get message lineage for context (if we have a parent)
           context-messages (when parent-msg-id
                              (db/get-message-lineage @conn parent-msg-id))

           ;; Build full message history for query relaxation
           all-messages     (-> (messages->context (or context-messages []))
                                (conj {:role :user
                                       :text query
                                       :message/role :user
                                       :message/text query}))

           ;; Initialize execution state
           _                (swap! !playground-executions assoc execution-id
                                    {:status            :running
                                     :stage             :init
                                     :streaming-content ""
                                     :events            []
                                     :action-trace      []
                                     :results           {}
                                     :live-status       nil
                                     :error             nil
                                     :started-at        (str (java.time.Instant/now))
                                     :tenant            effective-tenant
                                     :dataset-config-key canonical-dataset-config-key
                                     :agent-id          effective-agent-id
                                     :conversation-id   actual-convo-id
                                     :query             query
                                     :user-id           user-id
                                    :user-msg-id       user-msg-id
                                    :parent-msg-id     parent-msg-id
                                    :branch-index      branch-index})

           ;; Build params for reranking
           ;; Uses 3-level precedence: playground config > pipeline config > global default
           rag-params       {:conversation-id       actual-convo-id
                             :execution-id          execution-id
                             :tenant                effective-tenant
                             :dataset-config-key    canonical-dataset-config-key
                             ;; Carry the agent identity into rag-params so
                             ;; `execute-skill-graph` can thread it into the
                             ;; skills layer's `opts` (and from there into
                             ;; ambient-ctx :agent-id). Without this the agent
                             ;; skill sees `:agent-id nil` and any code that
                             ;; differentiates by agent (e.g. the loop's
                             ;; `enrichment-mode-agent?` predicate) silently
                             ;; falls back to default behavior — exactly the
                             ;; failure mode observed in the self-improve-agent
                             ;; playground smoke traces 2026-05-18T15-21-26 / 15-42-59.
                             :agent-id              effective-agent-id
                             ;; Resolve the agent's :skill-params and let
                             ;; build-playground-skill-params layer it
                             ;; between dataset-config defaults and UI
                             ;; overrides. `agents-db/get-agent` returns
                             ;; nil for unknown agent-ids; we fall back
                             ;; to {} so the merge still has the right
                             ;; shape (no behaviour change vs the
                             ;; pre-agent-skill-params world).
                             :skill-params          (build-playground-skill-params
                                                      dataset-config
                                                      config
                                                      query-relax-prompt
                                                      rag-generate-prompt
                                                      (or (:skill-params
                                                            (agents-db/get-agent
                                                              @conn
                                                              effective-agent-id))
                                                          {}))
                             :original_user_query   query
                             :translated_user_query query
                             ;; :user-model = the user's explicit override
                             ;; (nil if the picker is on "Default"). :selected-model
                             ;; is the resolved value used purely for display
                             ;; (response payload, conversation topic) and always
                             ;; carries something — it doesn't drive opts.model.
                             :user-model            (when-let [m (:model config)]
                                                      (when-not (clojure.string/blank? m) m))
                             :selected-model        (if (llm/use-azure-openai effective-tenant)
                                                    (cfg/get {:tenant effective-tenant} :services :azure-openai :deployment-name)
                                                    (or (:model config)
                                                        (:synthesis-model dataset-config)
                                                        "gpt-4o"))
                             ;; Rerank parameters
                             :rerankTopkChunks      (or (:rerank-top-k config)
                                                        (:rerank-top-k dataset-config))
                             ;; Playground exercises full-RAG flow → :rag-* values.
                             :rerankMaxChunkLength  (or (:rerank-max-chunk-length config)
                                                        (:rerank-rag-max-chunk-length dataset-config))
                             :rerankMaxLength       (or (:rerank-max-total-length config)
                                                        (:rerank-rag-max-total-length dataset-config))
                             ;; Context parameters
                             :contextTopkChunks     (or (:context-top-k config)
                                                        (:rerank-rag-context-top-k dataset-config))
                             :contextMinChunks      (or (:context-min-chunks config)
                                                        (:rerank-context-min-chunks dataset-config)
                                                        8)
                             :contextRelativeScoreThreshold (or (:context-relative-score-threshold config)
                                                                (:rerank-context-relative-score-threshold dataset-config)
                                                                0.85)
                             :contextMaxChunkLength (or (:context-max-chunk-length config)
                                                        (:rerank-rag-context-max-chunk-length dataset-config))
                             :maxContextLength      (or (:context-max-total-length config)
                                                        (:rerank-rag-max-context-length dataset-config))
                             ;; Collection names
                             :docsCollectionName    (:docs-collection dataset-config)
                             :chunksCollectionName  (:chunks-collection dataset-config)
                             :phrasesCollectionName (:phrases-collection dataset-config)
                             ;; Prompts (use new kebab-case property names)
                             :promptRagQueryRelax   (or query-relax-prompt
                                                        (:query-planner-prompt dataset-config))
                             :promptRagGenerate     (or rag-generate-prompt
                                                        (:synthesis-generation-prompt dataset-config))
                             ;; Retrieval merge overrides
                             :retrieveStrategyWeights           (:retrieval-strategy-weights dataset-config)
                             :retrieveStrategyContributionCaps  (:retrieval-strategy-contribution-caps dataset-config)}

           ;; Build Typesense opts for config resolution with explicit tenant/dataset-config-key
           ts-opts          {:tenant      effective-tenant
                             :runtime-config-key effective-runtime-config-key}
           resolved-runtime-context
                            {:execution-id execution-id
                             :tenant effective-tenant
                             :dataset-config-key canonical-dataset-config-key
                             :runtime-config-key effective-runtime-config-key
                             :agent-id effective-agent-id
                             :runtime-config-source (:source runtime-config-result)
                             :runtime-node-id (get-in runtime-config-result [:node :config.node/id])
                             :runtime-config-traces (:traces runtime-config-result)
                             :runtime-config-error nil
                             :collections {:docs-collection (:docs-collection dataset-config)
                                           :chunks-collection (:chunks-collection dataset-config)
                                           :phrases-collection (:phrases-collection dataset-config)}}]

      (update-execution-results! execution-id :resolved-runtime-context resolved-runtime-context)
      (t/log! :debug [:playground-chat/resolved-runtime-context resolved-runtime-context])
      (emit-execution-event! execution-id
                             (skill-events/request-started execution-id query))

       ;; Execute pipeline in a future to not block
       (future
         (try
           (t/log! :info [:playground-chat/using-skills {:execution-id execution-id}])
           (let [result        (execute-skill-graph
                                execution-id
                                query
                                all-messages
                                rag-params
                                config
                                ts-opts)
                 response-text (:response result)
                 diagnostics   (:diagnostics result)]

             (if (:error result)
               (do
                 (update-execution! execution-id
                                    {:status     :error
                                     :error      (get-in result [:error :error-message])
                                     :error-type (str (get-in result [:error :error-type]))})
                 (emit-execution-event! execution-id
                                        (skill-events/request-failed
                                         (get-in result [:error :error-message]))))

               (let [assistant-msg-result (db/queue-playground-assistant-msg!
                                           actual-convo-id
                                           response-text
                                           diagnostics
                                           execution-id
                                           user-msg-id
                                           0
                                           ;; After the background-worker
                                           ;; actually transacts the
                                           ;; assistant msg, stamp
                                           ;; :assistant-msg-committed-at
                                           ;; on the execution. The
                                           ;; playground UI watches this
                                           ;; key as a reactive trigger
                                           ;; for refetching
                                           ;; `fetch-conversation-tree`,
                                           ;; so the assistant bubble
                                           ;; appears as soon as the row
                                           ;; lands in Datahike rather
                                           ;; than only after a full page
                                           ;; reload (bug #74,
                                           ;; 2026-05-19).
                                           {:callback
                                            (fn [_]
                                              (update-execution! execution-id
                                                                 {:assistant-msg-committed-at
                                                                  (str (java.time.Instant/now))}))})]
                 (update-execution! execution-id
                                    {:assistant-msg-id (:message/id assistant-msg-result)})
                 (update-execution! execution-id
                                    {:status       (or (:status result) :complete)
                                     :stage        :complete
                                     :completed-at (str (java.time.Instant/now))})
                 (doseq [issue (:backend-issues diagnostics)]
                   (emit-execution-event! execution-id
                                          (skill-events/warning-raised
                                           {:code (or (:issue-type issue) :backend-issue)
                                            :message (or (:message issue) "Backend issue detected")
                                            :details issue})))
                 (when (:auto-filter-fallback diagnostics)
                   (emit-execution-event! execution-id
                                          (skill-events/warning-raised
                                           {:code :auto-filter-fallback
                                            :message "Auto-filter fallback used unfiltered retrieval"})))
                 (emit-execution-event! execution-id
                                        (skill-events/response-finalized
                                         {:text response-text
                                          :status (:status result)
                                          :clarification-request (:clarification-request result)
                                          :diagnostics diagnostics})))))
            (catch Exception e
              (t/log! :error [:playground-chat/pipeline-error
                              {:execution-id  execution-id
                               :error-message (.getMessage e)
                               :error-type    (str (type e))}])
              ;; Gather Typesense diagnostics on error
              (let [expected-collections [(:docs-collection dataset-config)
                                          (:chunks-collection dataset-config)
                                          (:phrases-collection dataset-config)]
                    ts-diagnostics       (try
                                           (get-typesense-diagnostics expected-collections ts-opts)
                                           (catch Exception diag-e
                                             {:diagnostic-error (.getMessage diag-e)}))]
                (update-execution! execution-id
                                   {:status                :error
                                    :error                 (.getMessage e)
                                    :error-type            (str (type e))
                                    :typesense-diagnostics ts-diagnostics
                                    ;; Add runtime dataset scope info for debugging config resolution
                                    :debug-info            {:dataset-ref       {:tenant effective-tenant
                                                                                 :dataset-config-key effective-dataset-config-key}
                                                            :effective-tenant effective-tenant
                                                            :dataset-config-key effective-dataset-config-key
                                                            :runtime-config-key effective-runtime-config-key
                                                            :dataset-config   (select-keys dataset-config [:id :name :docs-collection
                                                                                                           :chunks-collection :phrases-collection])}
                                    :completed-at          (str (java.time.Instant/now))})
                (emit-execution-event! execution-id
                                       (skill-events/request-failed (.getMessage e)))))))

       ;; Return execution ID, conversation ID, and user message ID immediately
       {:execution-id    execution-id
        :conversation-id actual-convo-id
        :user-msg-id     user-msg-id})))
