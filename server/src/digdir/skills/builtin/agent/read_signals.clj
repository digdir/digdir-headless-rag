(ns digdir.skills.builtin.agent.read-signals
  "LLM-first read-time evidence planning and local sufficiency signals."
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [digdir.config.accessor :as cfg]
   [digdir.llm.openai :as llm]
   [digdir.llm.structured-eval :as se]
   [digdir.llm.client :as openai]))

(def ^:private read-evaluator-system-prompt
  (str
   "You are the read-time evidence evaluator for a RAG coding/research agent.\n"
   "\n"
   "Your job is to inspect only the newly read chunks and decide what they add to the current evidence state.\n"
   "\n"
   "Do not answer the user's question.\n"
   "Do not use prior knowledge.\n"
   "Do not guess.\n"
   "Prefer false negatives over false positives.\n"
   "\n"
   "You must evaluate:\n"
   "- which required claims are explicitly supported by the new chunks\n"
   "- which claims are partially supported\n"
   "- which claims remain unsupported\n"
   "- whether the new chunks introduce contradictions\n"
   "- whether the evidence is on the correct scope for the user's query\n"
   "- the best local next action hint\n"
   "\n"
   "Return JSON only, matching the provided schema exactly.\n"
   "Allowed status values: support-found, gap-remaining, conflicting, unclear.\n"
   "Allowed scope_assessment values: aligned, partially-aligned, wrong-scope, ambiguous.\n"
   "Allowed support values: explicit, partial.\n"
   "Allowed remaining_gaps.reason values: not-addressed, ambiguous, wrong-scope, contradiction.\n"
   "Allowed next_action_hint values: read-more, re-search, ask-clarification, finalize."))

(def ^:private read-evaluator-response-shape
  {:status "support-found | gap-remaining | conflicting | unclear"
   :scope_assessment "aligned | partially-aligned | wrong-scope | ambiguous"
   :supported_claims [{:claim_id "claim-id"
                       :support "explicit | partial"
                       :chunk_ids ["chunk-id"]
                       :notes "optional short explanation"}]
   :remaining_gaps [{:claim_id "claim-id"
                     :reason "not-addressed | ambiguous | wrong-scope | contradiction"
                     :critical true}]
   :contradictions [{:claim_id "claim-id"
                     :chunk_ids ["chunk-a" "chunk-b"]
                     :summary "optional short contradiction summary"}]
   :next_action_hint "read-more | re-search | ask-clarification | finalize"
   :confidence 0.0})

(def ^:private allowed-statuses
  #{:support-found :gap-remaining :conflicting :unclear})

(def ^:private allowed-scope-assessments
  #{:aligned :partially-aligned :wrong-scope :ambiguous})

(def ^:private allowed-support-levels
  #{:explicit :partial})

(def ^:private allowed-gap-reasons
  #{:not-addressed :ambiguous :wrong-scope :contradiction})

(def ^:private allowed-next-action-hints
  #{:read-more :re-search :ask-clarification :finalize})

(defn build-evidence-plan
  [query query-intent]
  (let [{:keys [answer-type entity year-or-date metric]} query-intent
        answer-type (or answer-type :lookup)
        base-claims (cond-> []
                      (seq entity)
                      (conj {:claim-id :target-entity
                             :text (str "Evidence is about " entity)
                             :kind :scope
                             :critical? true})
                      (seq metric)
                      (conj {:claim-id :target-metric
                             :text (str "Evidence mentions metric " metric)
                             :kind :metric
                             :critical? true})
                      (seq year-or-date)
                      (conj {:claim-id :target-period
                             :text (str "Evidence matches period " year-or-date)
                             :kind :time
                             :critical? true}))]
    {:query query
     :query-intent query-intent
     :required-claims
     (vec
      (concat
       base-claims
       (case answer-type
         :numeric-fact [{:claim-id :requested-value
                         :text "Evidence includes an explicit value"
                         :kind :value
                         :critical? true}]
         :comparison [{:claim-id :comparison-evidence
                       :text "Evidence supports a comparison"
                       :kind :comparison
                       :critical? true}]
         :lookup [{:claim-id :topic-match
                   :text "Evidence directly matches the topic"
                   :kind :topic
                   :critical? true}
                  {:claim-id :answer-bearing-evidence
                   :text "Evidence contains answer-bearing details"
                   :kind :support
                   :critical? true}]
         [{:claim-id :topic-match
           :text "Evidence directly matches the topic"
           :kind :topic
           :critical? true}
          {:claim-id :answer-bearing-evidence
           :text "Evidence contains answer-bearing details"
           :kind :support
           :critical? true}])))}))

(defn- fallback-read-signal
  "Conservative signal emitted when the local LLM evaluator fails.

   Never claims semantic support: status stays :unclear, every required claim
   from the evidence plan flows through as an open gap, and the controller is
   nudged to :re-search rather than finalize. Bias toward false negatives is
   intentional — the deterministic aggregator treats supported-claims as real
   coverage, so a degraded heuristic match here would silently finalize runs
   that the LLM evaluator refused to finalize."
  [evidence-plan reason]
  (let [remaining-gaps (->> (:required-claims evidence-plan)
                            (mapv (fn [{:keys [claim-id critical?]}]
                                    {:claim-id claim-id
                                     :reason :not-addressed
                                     :critical? (boolean critical?)
                                     :critical (boolean critical?)})))]
    {:status :unclear
     :scope-assessment :ambiguous
     :supported-claims []
     :partial-claims []
     :remaining-gaps remaining-gaps
     :contradictions []
     :next-action-hint :re-search
     :confidence 0.0
     :degraded? true
     :degraded-reason (or reason :local-evaluator-failed)
     :evaluation-mode :degraded-fallback}))

(defn- selected-model
  [tenant model]
  (or model
      (if (llm/use-azure-openai tenant)
        (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
        (cfg/get {:tenant tenant} :services :azure-openai :model-name))))

(defn default-llm-fn
  "Default chat-completion implementation for read-time local evaluation."
  [tenant messages _tools model temperature]
  (let [params {:model (selected-model tenant model)
                :messages messages
                :temperature (or temperature 0.0)}]
    (if (llm/use-azure-openai tenant)
      (openai/create-chat-completion
       params
       {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
        :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
        :impl :azure})
      (openai/create-chat-completion params))))

(defn- chunk-title
  [chunk]
  (or (:title chunk)
      (some (fn [value]
              (when (and (map? value) (contains? value :title))
                (:title value)))
            (vals chunk))))

(defn- open-claims-for-prompt
  [evidence-plan open-claims]
  (let [claims (if (seq open-claims)
                 open-claims
                 (:required-claims evidence-plan))]
    (mapv (fn [{:keys [claim-id text kind critical? critical]}]
            {:claim_id (name claim-id)
             :text text
             :kind (some-> kind name)
             :critical (boolean (or critical? critical))})
          claims)))

(defn- chunk-payload
  [chunk]
  {:chunk_id (:chunk_id chunk)
   :doc_num (:doc_num chunk)
   :chunk_index (:chunk_index chunk)
   :title (chunk-title chunk)
   :metadata (:metadata chunk)
   :content (or (:content_markdown chunk) "")})

(defn build-read-evaluator-prompt
  [query query-intent evidence-plan open-claims chunks]
  (str "User query:\n"
       (or query "")
       "\n\nQuery intent JSON:\n"
       (json/write-str query-intent)
       "\n\nEvidence plan JSON:\n"
       (json/write-str
        {:required_claims
         (mapv (fn [{:keys [claim-id text kind critical?]}]
                 {:claim_id (name claim-id)
                  :text text
                  :kind (some-> kind name)
                  :critical (boolean critical?)})
               (or (:required-claims evidence-plan) []))})
       "\n\nCurrently unresolved claims JSON:\n"
       (json/write-str (open-claims-for-prompt evidence-plan open-claims))
       "\n\nNewly read chunks JSON:\n"
       (json/write-str (mapv chunk-payload chunks))
       "\n\nReturn JSON only.\n"
       "Use exactly this shape:\n"
       (json/write-str read-evaluator-response-shape)))

;; JSON parsing, enum normalization, and confidence clamping now live in
;; digdir.llm.structured-eval. Use se/parse-json-response, se/->validated-enum,
;; and se/clamp-confidence below.

(defn- claim-id-index
  [evidence-plan]
  (reduce (fn [index {:keys [claim-id]}]
            (let [aliases (->> [claim-id
                                (when (keyword? claim-id) (name claim-id))
                                (str claim-id)]
                               (remove nil?)
                               set)]
              (reduce #(assoc %1 %2 claim-id) index aliases)))
          {}
          (or (:required-claims evidence-plan) [])))

(defn- resolve-claim-id
  [claim-index raw-claim-id]
  (or (get claim-index raw-claim-id)
      (when (keyword? raw-claim-id)
        (get claim-index (name raw-claim-id)))
      (when (string? raw-claim-id)
        (or (get claim-index (keyword raw-claim-id))
            (get claim-index (str ":" raw-claim-id))))
      raw-claim-id))

(defn- normalize-supported-claim
  [claim-index entry]
  (let [claim-id (resolve-claim-id claim-index (or (:claim_id entry) (:claim-id entry)))
        support (se/->validated-enum (or (:support entry) (:support_level entry) (:support-level entry))
                                allowed-support-levels
                                :support)
        chunk-ids (->> (or (:chunk_ids entry) (:chunk-ids entry) [])
                       (mapv str))]
    {:claim-id claim-id
     :support support
     :support-level support
     :chunk-ids chunk-ids
     :notes (some-> (or (:notes entry) (:note entry))
                    str
                    str/trim
                    not-empty)}))

(defn- normalize-gap
  [claim-index evidence-plan entry]
  (let [claim-id (resolve-claim-id claim-index (or (:claim_id entry) (:claim-id entry)))
        claim-spec (some #(when (= claim-id (:claim-id %)) %) (:required-claims evidence-plan))
        critical? (boolean (or (:critical entry)
                               (:critical? entry)
                               (:critical? claim-spec)))]
    {:claim-id claim-id
     :reason (se/->validated-enum (or (:reason entry) :not-addressed)
                             allowed-gap-reasons
                             :reason)
     :critical critical?
     :critical? critical?}))

(defn- normalize-contradiction
  [claim-index entry]
  {:claim-id (some->> (or (:claim_id entry) (:claim-id entry))
                      (resolve-claim-id claim-index))
   :chunk-ids (->> (or (:chunk_ids entry) (:chunk-ids entry) [])
                   (mapv str))
   :summary (some-> (or (:summary entry) (:notes entry))
                    str
                    str/trim
                    not-empty)})

(defn normalize-read-signal
  [raw-signal evidence-plan]
  (let [claim-index (claim-id-index evidence-plan)
        status (se/->validated-enum (:status raw-signal) allowed-statuses :status)
        scope-assessment (se/->validated-enum (:scope_assessment raw-signal)
                                         allowed-scope-assessments
                                         :scope_assessment)
        supported-claims (mapv #(normalize-supported-claim claim-index %)
                               (or (:supported_claims raw-signal) (:supported-claims raw-signal) []))
        remaining-gaps (mapv #(normalize-gap claim-index evidence-plan %)
                             (or (:remaining_gaps raw-signal) (:remaining-gaps raw-signal) []))
        contradictions (mapv #(normalize-contradiction claim-index %)
                             (or (:contradictions raw-signal) []))
        next-action-hint (se/->validated-enum (:next_action_hint raw-signal)
                                         allowed-next-action-hints
                                         :next_action_hint)
        confidence (se/clamp-confidence (:confidence raw-signal))]
    {:status status
     :scope-assessment scope-assessment
     :supported-claims supported-claims
     :partial-claims (->> supported-claims
                          (filter #(= :partial (:support-level %)))
                          vec)
     :remaining-gaps remaining-gaps
     :contradictions contradictions
     :next-action-hint next-action-hint
     :confidence confidence
     :degraded? false
     :evaluation-mode :llm}))

(defn read-signal->json-payload
  [signal]
  {:status (name (or (:status signal) :unclear))
   :scope_assessment (name (or (:scope-assessment signal) :ambiguous))
   :supported_claims
   (mapv (fn [{:keys [claim-id support-level chunk-ids notes]}]
           (cond-> {:claim_id (name claim-id)
                    :support (name (or support-level :partial))
                    :chunk_ids (vec (or chunk-ids []))}
             notes
             (assoc :notes notes)))
         (or (:supported-claims signal) []))
   :remaining_gaps
   (mapv (fn [{:keys [claim-id reason critical critical?]}]
           {:claim_id (name claim-id)
            :reason (name (or reason :not-addressed))
            :critical (boolean (or critical critical?))})
         (or (:remaining-gaps signal) []))
   :contradictions
   (mapv (fn [{:keys [claim-id chunk-ids summary]}]
           (cond-> {:chunk_ids (vec (or chunk-ids []))}
             claim-id
             (assoc :claim_id (name claim-id))
             summary
             (assoc :summary summary)))
         (or (:contradictions signal) []))
   :next_action_hint (name (or (:next-action-hint signal) :read-more))
   :confidence (double (or (:confidence signal) 0.0))})

(defn evaluate-read
  ([query query-intent evidence-plan chunks]
   (evaluate-read query query-intent evidence-plan [] chunks nil))
  ([query query-intent evidence-plan open-claims chunks]
   (evaluate-read query query-intent evidence-plan open-claims chunks nil))
  ([query query-intent evidence-plan open-claims chunks {:keys [tenant llm-fn model temperature]}]
   (se/evaluate
    {:label "read-signal evaluator"
     :llm-fn (or llm-fn (partial default-llm-fn tenant))
     :system-prompt read-evaluator-system-prompt
     :user-prompt (build-read-evaluator-prompt query query-intent evidence-plan
                                               open-claims chunks)
     :model model
     :temperature (or temperature 0.0)
     :normalize-fn #(normalize-read-signal % evidence-plan)
     :fallback-fn (fn [_]
                    (fallback-read-signal evidence-plan :local-evaluator-failed))})))
