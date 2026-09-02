(ns digdir.playground.action-trace
  "Canonical execution-action records shared by live and completed Playground
   views. Graph progress is converted once, at the execution boundary; UI code
   renders these records directly and never reconstructs actions from timings."
  (:require [clojure.string :as str]))

(defn skill-name
  [skill-id]
  (cond
    (keyword? skill-id) (name skill-id)
    (string? skill-id) (last (str/split skill-id #"/"))
    :else nil))

(defn action-kind
  "Stable semantic kind for an executed skill. Unknown graph steps remain
   visible as generic tools without leaking presentation labels into data."
  [skill-id]
  (case (skill-name skill-id)
    "query-planner" :plan
    "retrieval" :search
    "rerank" :rerank
    "overview-evidence-gate" :sufficiency
    "overview-synthesis" :synthesis
    "overview-finalize" :finalize
    "enrichment-analyze-corpus" :enrichment-analyze
    "enrichment-propose-questions" :enrichment-propose
    "enrichment-propose-phrases" :enrichment-propose
    "enrichment-apply-questions" :enrichment-apply
    "enrichment-apply-phrases" :enrichment-apply
    "enrichment-eval-suite" :enrichment-eval
    :tool))

(defn- agent-internal-skill?
  [skill-id]
  (str/starts-with? (or (skill-name skill-id) "") "agent"))

(def ^:private compact-chunk-keys
  [:chunk_id :chunk-id :doc_num :doc-num :chunk_index :chunk-index
   :content_length :content-length :title :url :rank :rerank-score :score
   :search-types :retrieval-boosts])

(defn- compact-chunk
  [chunk]
  (select-keys (or chunk {}) compact-chunk-keys))

(defn- document-count
  [chunks]
  (count (distinct (keep #(or (:doc_num %) (:doc-num %) (:document-id %))
                         (or chunks [])))))

(defn- citation-count
  [citations]
  (count (or citations [])))

(defn- generic-result
  [outputs]
  (let [outputs (or outputs {})
        chunks (:chunks outputs)
        context-docs (:context-docs outputs)]
    (cond-> (dissoc outputs :chunks :context-docs)
      (some? chunks) (assoc :chunk-count (count chunks))
      (some? context-docs) (assoc :context-document-count (count context-docs)))))

(defn canonical-result
  "Project a skill's outputs into the persisted action-result contract.

   Large retrieval/context payloads are represented by counts and compact
   source identities. The projection happens before both live rendering and
   persistence, so those views consume byte-for-byte equivalent semantics."
  [skill-id outputs]
  (let [outputs (or outputs {})
        kind (action-kind skill-id)]
    (case kind
      :plan
      {:queries (vec (or (:queries outputs) []))
       :user-intent (:user-intent outputs)}

      :search
      (let [attribution (or (:search-attribution outputs) {})]
        {:candidate-count (or (:merged attribution) (count (:chunks outputs)))
         :phrase-count (or (:phrase attribution) 0)
         :content-count (or (:content attribution) 0)
         :metadata-count (or (:metadata attribution) 0)
         :auto-filter-applied (:auto-filter-applied attribution)
         :auto-filter-fallback (:auto-filter-fallback attribution)})

      :rerank
      (let [chunks (vec (or (:chunks outputs) []))]
        {:chunks (mapv compact-chunk (take 20 chunks))
         :selected-count (count chunks)
         :distinct-document-count (document-count chunks)})

      :sufficiency
      (let [summary (or (:evidence-summary outputs) {})]
        {:sufficient? (true? (:evidence-sufficient? outputs))
         :decline-reason (:evidence-decline-reason outputs)
         :summary summary
         :context-document-count (or (:context-doc-count summary) 0)
         :distinct-document-count (or (:distinct-document-count summary) 0)})

      :synthesis
      (let [response (:overview-response outputs)]
        {:response response
         :character-count (count (or response ""))
         :citation-count (citation-count (:citations outputs))
         :insufficient-context (:insufficient-context outputs)
         :citation-validation (:citation-validation outputs)})

      :finalize
      (let [response (or (:response outputs) (:overview outputs))
            declined? (true? (:overview-declined? outputs))]
        {:published? (and (not declined?) (not (str/blank? (or response ""))))
         :decline-reason (:overview-decline-reason outputs)
         :character-count (count (or response ""))
         :citation-count (citation-count (:citations outputs))})

      (generic-result outputs))))

(defn progress->action
  "Convert one graph progress event into a canonical action record. Returns nil
   for non-step progress events such as agent reasoning and response chunks."
  [{:keys [event step-id skill-id duration-ms outputs error]}]
  (when (and (contains? #{:step/started :step/completed :step/skipped
                          :step/defaulted :step/failed}
                        event)
             ;; ReAct agents already publish their purpose-built agent trace.
             ;; Their graph-internal bookkeeping steps are not user actions and
             ;; would otherwise duplicate that trace with large workspace maps.
             (not (agent-internal-skill? skill-id)))
    (cond-> {:id step-id
             :skill-id skill-id
             :kind (action-kind skill-id)
             :status (case event
                       :step/started :running
                       :step/completed :ok
                       :step/skipped :skipped
                       :step/defaulted :defaulted
                       :step/failed :error)}
      (some? duration-ms) (assoc :duration-ms duration-ms)
      (= event :step/completed) (assoc :result (canonical-result skill-id outputs))
      (= event :step/skipped) (assoc :result {})
      (= event :step/defaulted) (assoc :result {})
      (= event :step/failed) (assoc :error (or (:error-message error)
                                               (:message error)
                                               (some-> error str))))))

(defn upsert-action
  "Insert a newly started action or replace its later status/result in place,
   retaining deterministic graph execution order."
  [trace action]
  (let [trace (vec (or trace []))]
    (if-let [idx (first (keep-indexed (fn [idx existing]
                                       (when (= (:id existing) (:id action)) idx))
                                     trace))]
      (assoc trace idx (merge (nth trace idx) action))
      (conj trace action))))

(defn record-progress
  [trace progress]
  (if-let [action (progress->action progress)]
    (upsert-action trace action)
    (vec (or trace []))))

(defn normalize-action-trace
  "Stable collection shape at persistence/read boundaries."
  [trace]
  (mapv (fn [action]
          (-> action
              (update :kind #(or % (action-kind (:skill-id action))))
              (update :status #(or % :ok))))
        (or trace [])))
