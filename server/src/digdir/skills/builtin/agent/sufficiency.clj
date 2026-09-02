(ns digdir.skills.builtin.agent.sufficiency
  "Formal sufficiency-gate evaluation for the agentic loop."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [digdir.llm.prompt-fragments :as prompt-fragments]
            [digdir.llm.structured-eval :as se]))

(def ^:private evaluator-system-prompt
  (str
   "You are the sufficiency gate for a solver-style RAG agent.\n"
   "Judge whether the current evidence is sufficient to answer the user query.\n"
   "Check explicitly for:\n"
   "- Metric precision mismatches: the query asks for one metric/year/scope, the evidence provides another.\n"
   "- Scope alignment: the query asks about one entity or document family, the evidence is about another.\n"
   "- Broad platform ambiguity: when the query can refer to multiple products, APIs, or workflows, prefer ask-clarification over guessing.\n"
   "- Contradictions: sources disagree on the value or interpretation.\n"
   "Return JSON with keys: status, reasoning, missing_info, contradiction_detected, suggested_strategy.\n"
   "When suggested_strategy is ask-clarification, also return clarification_question, optional options, and context_summary.\n"
   "Allowed status values: sufficient, insufficient, conflicting, off-topic.\n"
   "Allowed suggested_strategy values: re-search, read-more, ask-clarification, finalize.\n"
   "\n"
   "Reasoning / clarification-question language: " prompt-fragments/same-language-rule))

(def ^:private metric-aliases
  {"utførte årsverk" ["utførte årsverk" "utførte arsverk" "utforte årsverk" "utforte arsverk" "årsverk" "arsverk" "fte"]
   "avtalte årsverk" ["avtalte årsverk" "avtalte arsverk" "årsverk" "arsverk" "fte"]
   "årsverk" ["årsverk" "arsverk" "fte"]
   "ansatte" ["ansatte" "employees" "staff"]
   "employees" ["employees" "staff" "ansatte"]
   "fte" ["fte" "årsverk" "arsverk"]})

(defn- normalize-text
  [text]
  (-> (or text "")
      str/lower-case
      (str/replace #"[^\p{L}\p{N}\s-]" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn- extract-years
  [text]
  (->> (re-seq #"\b(?:19|20)\d{2}\b" (or text ""))
       distinct
       vec))

(defn- parse-int-safe
  [value]
  (try
    (Integer/parseInt (str value))
    (catch Exception _
      nil)))

(defn- query-year
  [query]
  (first (extract-years query)))

(defn- query-metric
  [query]
  (let [q (normalize-text query)]
    (cond
      (str/includes? q "utførte årsverk") "utførte årsverk"
      (str/includes? q "utførte arsverk") "utførte årsverk"
      (str/includes? q "utforte årsverk") "utførte årsverk"
      (str/includes? q "utforte arsverk") "utførte årsverk"
      (str/includes? q "avtalte årsverk") "avtalte årsverk"
      (str/includes? q "avtalte arsverk") "avtalte årsverk"
      (str/includes? q "årsverk") "årsverk"
      (str/includes? q "arsverk") "årsverk"
      (str/includes? q "ansatte") "ansatte"
      (str/includes? q "employees") "employees"
      (str/includes? q "fte") "fte"
      :else nil)))

(defn- query-entity
  [query]
  (let [words (re-seq #"[A-ZÆØÅ][A-Za-zÆØÅæøå0-9.-]+" (or query ""))
        question-words #{"Hva" "Hvem" "Hvor" "Hvordan" "Hvorfor" "Når"
                         "What" "Who" "Where" "How" "Why" "When"
                         "Compare" "Sammenlign"}]
    (when-let [tokens (seq (remove question-words words))]
      (str/join " " tokens))))

(defn- query-answer-type
  [query]
  (let [q (normalize-text query)]
    (cond
      (or (str/includes? q "sammenlign")
          (str/includes? q "compare")
          (str/includes? q "forskjell")
          (str/includes? q "difference"))
      :comparison

      (or (str/includes? q "hvor mange")
          (str/includes? q "how many")
          (str/includes? q "antall")
          (str/includes? q "what is the number")
          (str/includes? q "årsverk")
          (str/includes? q "arsverk")
          (str/includes? q "employees")
          (str/includes? q "ansatte")
          (str/includes? q "fte"))
      :numeric-fact

      :else
      :lookup)))

(declare candidate-text candidate-metric-values)

(defn- comparison-query?
  [query evidence-summary]
  (or (= :comparison (get-in evidence-summary [:query-intent :answer-type]))
      (= :comparison (query-answer-type query))))

(defn- comparison-sufficient?
  [query evidence-summary candidates]
  (let [text (str/join " " (map candidate-text candidates))
        numbers (->> candidates
                     (mapcat #(candidate-metric-values % nil nil))
                     distinct
                     vec)]
    (and (comparison-query? query evidence-summary)
         (or (and (str/includes? text "avtalte")
                  (or (str/includes? text "utforte")
                      (str/includes? text "utførte")))
             (>= (count numbers) 2))
         (or (str/includes? text "forklaring")
             (str/includes? text "faktisk arbeid")
             (str/includes? text "budsjettert")
             (str/includes? text "planlagt")))))

(defn- compact-search-summary
  [entry]
  {:queries (vec (or (:queries entry) []))
   :result-count (or (:result-count entry) 0)
   :new-count (or (:new-count entry) 0)
   :fallback? (boolean (:fallback? entry))
   :chunk-summaries
   (mapv (fn [summary]
           (cond-> (select-keys summary [:chunk-id :doc-num :chunk-index :title :headers :content-length])
             ;; Surface enrichment evidence so the sufficiency evaluator
             ;; can treat a hypothetical-question match as strong evidence
             ;; for finalizing instead of triggering another read pass.
             (seq (:matched-questions summary))
             (assoc :matched-questions (:matched-questions summary))))
         (or (:chunk-summaries entry) []))})

(defn- content-preview
  [text max-chars]
  (let [text (or text "")]
    (if (> (count text) max-chars)
      (str (subs text 0 max-chars) "...")
      text)))

(defn build-evidence-summary
  "Build a compact, evaluator-friendly snapshot of the current workspace."
  [workspace query]
  (let [workspace-intent (or (:last-query-intent workspace) {})
        chunks (->> (vals (or (:chunks workspace) {}))
                    (sort-by (juxt (comp - #(or % 0) :retrieval-prior)
                                   (comp - #(or % 0) :hit-count)
                                   (comp - #(or % 0) :original-rank)))
                    (take 8)
                    (mapv (fn [chunk]
                            (cond-> {:chunk-id (:chunk_id chunk)
                                     :doc-num (:doc_num chunk)
                                     :chunk-index (:chunk_index chunk)
                                     :title (:title chunk)
                                     :metadata (:metadata chunk)
                                     :content-preview (content-preview (:content_markdown chunk) 500)}
                              (seq (:matched-questions chunk))
                              (assoc :matched-questions (:matched-questions chunk))))))
        latest-search (last (or (:search-history workspace) []))
        read-history (or (:read-history workspace) [])
        read-ids (->> read-history (mapcat :returned-chunk-ids) set)
        latest-summaries (vec (or (:chunk-summaries latest-search) []))
        unread-summaries (->> latest-summaries
                              (remove #(contains? read-ids (:chunk-id %)))
                              vec)
        range-candidate
        (some (fn [{:keys [doc-num chunk-index total-chunks]}]
                (when (and doc-num
                           (integer? chunk-index)
                           (or (nil? total-chunks) (> total-chunks 1)))
                  {:doc-num doc-num
                   :chunk-range {:from (max 0 (dec chunk-index))
                                 :to (if (integer? total-chunks)
                                       (min (dec total-chunks) (inc chunk-index))
                                       (inc chunk-index))}}))
              unread-summaries)]
    {:query query
     :query-intent {:answer-type (or (:answer-type workspace-intent)
                                     (query-answer-type query))
                    :entity (or (:entity workspace-intent)
                                (query-entity query))
                    :year-or-date (or (:year-or-date workspace-intent)
                                      (query-year query))
                    :metric (or (:metric workspace-intent)
                                (query-metric query))
                    :doc-family-preference (:doc-family-preference workspace-intent)
                    :scope-signals (vec (or (:scope-signals workspace-intent) []))}
     :budget-state (:budget-limits workspace)
     :search-history (mapv compact-search-summary (or (:search-history workspace) []))
     :read-history
     (mapv #(select-keys % [:mode :chunk-ids :doc-num :chunk-range :returned-count :content-length])
           read-history)
     :readable-chunks chunks
     :latest-search-queries (vec (or (:queries latest-search) []))
     :latest-search-summaries
     (mapv #(select-keys % [:chunk-id :doc-num :chunk-index :title :headers :content-length])
           latest-summaries)
     :unread-chunk-ids (->> unread-summaries (take 3) (mapv :chunk-id))
     :unread-range range-candidate}))

(defn build-evaluator-prompt
  "Low-temperature prompt body for an optional LLM-backed sufficiency pass."
  [query evidence-summary]
  (str "User query:\n" query
       "\n\nEvidence summary JSON:\n"
       (json/write-str evidence-summary)
       "\n\nReturn only JSON."))

(defn- candidate-text
  [candidate]
  (normalize-text
   (str (or (:title candidate) "")
        " "
        (or (:metadata candidate) "")
        " "
        (or (:headers candidate) "")
        " "
        (or (:content-preview candidate) ""))))

(defn- metric-terms
  [metric]
  (or (get metric-aliases metric)
      (when metric [metric])))

(defn- candidate-matches-year?
  [candidate year]
  (or (nil? year)
      (str/includes? (candidate-text candidate) (normalize-text year))))

(defn- candidate-matches-entity?
  [candidate entity]
  (let [entity (normalize-text entity)]
    (or (str/blank? entity)
        (str/includes? (candidate-text candidate) entity))))

(defn- candidate-matches-metric?
  [candidate metric]
  (let [terms (metric-terms metric)
        text (candidate-text candidate)]
    (or (empty? terms)
        (some #(str/includes? text (normalize-text %)) terms))))

(defn- metric-precision-mismatch?
  [candidate metric]
  (let [text (candidate-text candidate)]
    (cond
      (= metric "utførte årsverk")
      (and (str/includes? text "avtalte årsverk")
           (not (str/includes? text "utførte årsverk"))
           (not (str/includes? text "utforte arsverk")))

      (= metric "avtalte årsverk")
      (and (or (str/includes? text "utførte årsverk")
               (str/includes? text "utforte arsverk"))
           (not (str/includes? text "avtalte årsverk"))
           (not (str/includes? text "avtalte arsverk")))

      :else
      false)))

(defn- extract-numbers
  [text]
  (->> (re-seq #"\d+" (or text ""))
       (keep parse-int-safe)
       distinct
       vec))

(defn- candidate-metric-values
  [candidate _metric year]
  (let [text (or (:content-preview candidate)
                 (:title candidate)
                 "")
        numbers (extract-numbers text)
        ignore-values (into #{}
                            (keep parse-int-safe)
                            (extract-years text))]
    (->> numbers
         (remove ignore-values)
         (remove #(and year (= % (parse-int-safe year))))
         vec)))

(defn- readable-candidates
  [summary]
  (if (seq (:readable-chunks summary))
    (:readable-chunks summary)
    (:latest-search-summaries summary)))

(defn- numeric-evidence-query?
  [answer-type metric]
  (or (= answer-type :numeric-fact)
      (= answer-type :comparison)
      (some? metric)))

(defn- query-has-any?
  [query terms]
  (let [q (normalize-text query)]
    (some #(str/includes? q (normalize-text %)) terms)))

(defn- present-option-labels
  [candidates option-specs]
  (let [texts (map candidate-text candidates)]
    (->> option-specs
         (keep (fn [{:keys [label aliases]}]
                 (when (some (fn [text]
                               (some #(str/includes? text (normalize-text %)) aliases))
                             texts)
                   label)))
         distinct
         vec)))

(def ^:private product-scope-options
  [{:label "Altinn Studio" :aliases ["altinn studio" "studio"]}
   {:label "Altinn Apps" :aliases ["altinn apps" "altinn app" "containerized applications"]}
   {:label "Altinn Formidling" :aliases ["altinn formidling" "formidling" "broker" "managed file transfer" "mft"]}
   {:label "Altinn Melding" :aliases ["altinn melding" "melding"]}])

(def ^:private api-scope-options
  [{:label "Altinn Broker API" :aliases ["broker" "broker api" "overgangsløsning" "bridge"]}
   {:label "Altinn Formidling API" :aliases ["altinn formidling" "formidling" "managed file transfer" "mft"]}
   {:label "Altinn Melding API" :aliases ["altinn melding" "melding"]}])

(def ^:private authorization-scope-options
  [{:label "Altinn-apper" :aliases ["altinn apps" "app" "apper"]}
   {:label "Altinn-ressurser" :aliases ["ressursregister" "ressurser" "resource rights register" "resource rights"]}
   {:label "Fullmakter/delegering" :aliases ["fullmakt" "fullmakter" "deleg" "tilgangspakke" "rolle"]}])

(defn- clarification-decision
  [question options context-summary reasoning missing-info]
  {:status :insufficient
   :reasoning reasoning
   :missing-info (vec missing-info)
   :contradiction-detected? false
   :suggested-strategy :ask-clarification
   :clarification-question question
   :options (vec options)
   :context-summary context-summary})

(defn- ambiguity-clarification-decision
  [query candidates]
  (let [q (normalize-text query)
        altinn-query? (str/includes? q "altinn")
        product-options (present-option-labels candidates product-scope-options)
        api-options (present-option-labels candidates api-scope-options)
        authorization-options (present-option-labels candidates authorization-scope-options)]
    (cond
      (and altinn-query?
           (query-has-any? query ["api"])
           (>= (count api-options) 2))
      (clarification-decision
       (str "Hvilket Altinn 3 API mener du: " (str/join ", " api-options) "?")
       api-options
       "Kildene peker mot flere ulike Altinn 3 API-er, og spørsmålet spesifiserer ikke hvilket API du mener."
       "The evidence spans multiple plausible Altinn 3 APIs for this query."
       ["Need the relevant Altinn 3 API scope before answering."])

      (and altinn-query?
           (query-has-any? query ["autorisasjon" "tilgang" "fullmakt" "delegering"])
           (>= (count authorization-options) 2))
      (clarification-decision
       (str "Mener du autorisasjon for " (str/join ", " authorization-options) "?")
       authorization-options
       "Altinn 3 har flere autorisasjonsmodeller, og spørsmålet spesifiserer ikke hvilket autorisasjonsområde du mener."
       "The evidence spans multiple Altinn 3 authorization scopes for this query."
       ["Need the relevant Altinn 3 authorization scope before answering."])

      (and altinn-query?
           (query-has-any? query ["setter jeg opp" "sette opp" "komme i gang" "utvikler" "publiser" "publisere"])
           (>= (count product-options) 2))
      (clarification-decision
       (str "Hvilken del av Altinn 3 mener du: " (str/join ", " product-options) "?")
       product-options
       "Kildene peker mot flere ulike deler av Altinn 3, og spørsmålet er fortsatt for bredt til å svare presist."
       "The evidence spans multiple Altinn 3 products or workflows for this query."
       ["Need the relevant Altinn 3 product or workflow scope before answering."])

      :else
      nil)))

(def ^:private known-entity-aliases
  {"digdir" ["digdir" "digitaliseringsdirektoratet"]
   "altinn" ["altinn"]})

(defn- mentions-entity-alias?
  [text aliases]
  (some #(str/includes? text (normalize-text %)) aliases))

(defn- off-topic?
  [candidates entity]
  (let [entity-text (normalize-text entity)
        target-aliases (or (some (fn [[k aliases]]
                                   (when (str/includes? entity-text k)
                                     aliases))
                                 known-entity-aliases)
                           (when-not (str/blank? entity-text)
                             [entity-text]))
        alternate-aliases (->> known-entity-aliases
                               (remove (fn [[_ aliases]]
                                         (and target-aliases
                                              (some #(some (fn [target]
                                                             (= (normalize-text %) (normalize-text target)))
                                                           target-aliases)
                                                    aliases))))
                               (mapcat second)
                               vec)]
    (and (seq candidates)
         (seq target-aliases)
         (not-any? #(mentions-entity-alias? (candidate-text %) target-aliases) candidates)
         (some #(mentions-entity-alias? (candidate-text %) alternate-aliases) candidates))))

(defn- status-with-strategy
  [status reasoning missing-info contradiction? strategy]
  {:status status
   :reasoning reasoning
   :missing-info (vec missing-info)
   :contradiction-detected? contradiction?
   :suggested-strategy strategy})

(defn- insufficient-strategy
  [summary]
  (cond
    (seq (:unread-chunk-ids summary)) :read-more
    (:unread-range summary) :read-more
    :else :re-search))

(def ^:private stop-words
  #{"i" "på" "en" "et" "og" "er" "det" "den" "de" "for" "til" "av" "med"
    "om" "fra" "som" "jeg" "vi" "du" "kan" "meg" "seg" "sin" "sitt"
    "the" "a" "an" "is" "are" "in" "on" "to" "of" "with" "from"
    "how" "what" "who" "where" "when" "why" "do" "does" "did"
    "hva" "hvem" "hvor" "hvordan" "hvorfor" "når"})

(defn- query-topic-terms
  "Extract substantive topic terms from a query, excluding stop words and the entity."
  [query entity]
  (let [entity-words (set (map normalize-text (str/split (or entity "") #"\s+")))
        words (str/split (normalize-text query) #"\s+")]
    (->> words
         (remove str/blank?)
         (remove stop-words)
         (remove entity-words)
         (remove #(< (count %) 3))
         vec)))

(defn- evidence-addresses-query?
  "Check that the content previews contain at least some of the query's
   substantive topic terms (beyond just the entity name). This prevents
   the gate from rubber-stamping evidence that mentions the right entity
   but covers a completely different topic."
  [query entity candidates]
  (let [topic-terms (query-topic-terms query entity)]
    (or (< (count topic-terms) 2)
        (let [all-text (str/join " " (map candidate-text candidates))
              matched (count (filter #(str/includes? all-text %) topic-terms))]
          (>= matched (long (Math/ceil (/ (count topic-terms) 2))))))))

(defn- evaluate-sufficiency-heuristically
  [query evidence-summary]
  (let [{:keys [entity year-or-date metric answer-type]} (:query-intent evidence-summary)
        candidates (readable-candidates evidence-summary)
        entity (or entity (query-entity query))
        year (or year-or-date (query-year query))
        metric (or metric (query-metric query))
        entity-matched (filterv #(candidate-matches-entity? % entity) candidates)
        metric-matched (filterv #(candidate-matches-metric? % metric) entity-matched)
        year-matched (filterv #(candidate-matches-year? % year) metric-matched)
        precision-mismatches (filterv #(metric-precision-mismatch? % metric) entity-matched)
        numeric-query? (numeric-evidence-query? answer-type metric)
        metric-values (if numeric-query?
                        (->> year-matched
                             (mapcat #(candidate-metric-values % metric year))
                             distinct
                             vec)
                        [])
        all-years (->> candidates
                       (mapcat #(extract-years (candidate-text %)))
                       distinct
                       vec)
        strategy (insufficient-strategy evidence-summary)
        clarification (when-not numeric-query?
                        (ambiguity-clarification-decision query candidates))]
    (cond
      (comparison-sufficient? query evidence-summary candidates)
      (status-with-strategy
       :sufficient
       "The current evidence appears sufficient to answer the requested comparison."
       []
       false
       :finalize)

      (empty? candidates)
      (status-with-strategy
       :insufficient
       "No readable evidence has been gathered yet."
       ["Need readable evidence before answering."]
       false
       strategy)

      (off-topic? candidates entity)
      (status-with-strategy
       :off-topic
       (str "The current evidence does not align with the requested scope"
            (when entity (str " for " entity))
            ".")
       [(str "Need evidence that directly matches the requested scope"
             (when entity (str " for " entity))
             ".")]
       false
       :re-search)

      clarification
      clarification

      (and year (empty? year-matched) (seq metric-matched) (seq all-years))
      (status-with-strategy
       :insufficient
       (str "The evidence mentions the right topic, but not the requested time period " year ".")
       [(str "Need evidence for " year
             (when metric (str " and metric \"" metric "\""))
             ".")]
       false
       :re-search)

      (and metric (seq precision-mismatches) (empty? year-matched))
      (status-with-strategy
       :insufficient
       (str "The evidence mentions a related metric, but not the requested metric \"" metric "\".")
       [(str "Need an exact match for metric \"" metric "\".")]
       false
       strategy)

      (and numeric-query?
           (> (count metric-values) 1))
      (status-with-strategy
       :conflicting
       (str "The evidence contains conflicting values"
            (when metric (str " for \"" metric "\""))
            (when year (str " in " year))
            ": " (str/join ", " metric-values) ".")
       [(str "Need a canonical or scope-disambiguated value"
             (when metric (str " for \"" metric "\""))
             (when year (str " in " year))
             ".")]
       true
       strategy)

      (and (= answer-type :numeric-fact)
           metric
           (empty? metric-values))
      (status-with-strategy
       :insufficient
       "The evidence is on-topic, but it does not yet provide the requested concrete value."
       [(str "Need the exact reported value"
             (when metric (str " for \"" metric "\""))
             (when year (str " in " year))
             ".")]
       false
       strategy)

      (and (not numeric-query?)
           (not (evidence-addresses-query? query entity candidates)))
      (status-with-strategy
       :insufficient
       "The evidence mentions the right entity but does not appear to address the specific question."
       ["Need evidence that directly addresses the question topic."]
       false
       strategy)

      :else
      (status-with-strategy
       :sufficient
       "The current evidence appears sufficient to answer the query."
       []
       false
       :finalize))))

;; Semantic aliases that the LLM historically emits in place of the canonical
;; status/strategy keyword. Anything not in these maps falls through to
;; se/keyword-like, which handles case/underscore variants (e.g. "Sufficient",
;; "OFF_TOPIC") without forcing a degraded fallback.
(def ^:private status-aliases
  {:enough :sufficient
   :conflict :conflicting
   "enough" :sufficient
   "conflict" :conflicting})

(def ^:private strategy-aliases
  {:stop :finalize
   :answer-with-uncertainty :finalize
   "stop" :finalize
   "answer-with-uncertainty" :finalize})

(defn normalize-status
  [status]
  (se/resolve-alias-or-normalize status status-aliases))

(defn normalize-strategy
  [strategy]
  (se/resolve-alias-or-normalize strategy strategy-aliases))

(defn normalize-decision
  [decision]
  (let [decision (or decision {})]
    (cond-> {:status (normalize-status (:status decision))
             :reasoning (or (:reasoning decision) "No sufficiency reasoning captured.")
             :missing-info (vec (or (:missing-info decision)
                                    (:missing_info decision)
                                    []))
             :contradiction-detected? (boolean (or (:contradiction-detected? decision)
                                                   (:contradiction_detected decision)
                                                   (:contradiction_detected? decision)))
             :suggested-strategy (normalize-strategy (or (:suggested-strategy decision)
                                                         (:suggested_strategy decision)
                                                         :re-search))}
      (some-> (or (:clarification-question decision)
                  (:clarification_question decision))
              not-empty)
      (assoc :clarification-question (or (:clarification-question decision)
                                         (:clarification_question decision)))

      (some->> (or (:options decision) [])
               (keep not-empty)
               seq)
      (assoc :options (vec (keep not-empty (or (:options decision) []))))

      (some-> (or (:context-summary decision)
                  (:context_summary decision))
              not-empty)
      (assoc :context-summary (or (:context-summary decision)
                                  (:context_summary decision))))))

(defn- heuristic-decision
  "Heuristic fallback decision, marked :degraded? so traces can tell
   LLM-graded verdicts from heuristic ones."
  [query evidence-summary]
  (-> (evaluate-sufficiency-heuristically query evidence-summary)
      normalize-decision
      (assoc :degraded? true
             :degraded-reason :heuristic-fallback)))

(defn evaluate-sufficiency
  "Evaluate whether the current evidence is sufficient.

   Arity 2 always uses the heuristic evaluator and marks the result as degraded.
   Arity 3 runs the LLM via digdir.llm.structured-eval with code-fence-tolerant
   JSON parsing; on any failure it falls back to the heuristic path."
  ([query evidence-summary]
   (heuristic-decision query evidence-summary))
  ([query evidence-summary {:keys [llm-fn model temperature]}]
   (if-not llm-fn
     (heuristic-decision query evidence-summary)
     (se/evaluate
      {:label "sufficiency evaluator"
       :llm-fn llm-fn
       :system-prompt evaluator-system-prompt
       :user-prompt (build-evaluator-prompt query evidence-summary)
       :model model
       :temperature (or temperature 0.0)
       :normalize-fn #(assoc (normalize-decision %) :degraded? false)
       :fallback-fn (fn [_]
                      (heuristic-decision query evidence-summary))}))))

(defn format-read-more-hint
  [decision evidence-summary]
  (let [missing-info (or (:missing-info decision) [])
        chunk-ids (or (:unread-chunk-ids evidence-summary) [])
        range-hint (:unread-range evidence-summary)]
    (str "[SYSTEM: Sufficiency gate rejected the current evidence. "
         (:reasoning decision)
         (when (seq missing-info)
           (str " Missing info: " (str/join "; " missing-info) "."))
         " Read more from the current result set before searching again."
         (when (seq chunk-ids)
           (str " Suggested next call: read_chunks "
                (json/write-str {:chunk_ids chunk-ids})
                "."))
         (when range-hint
           (str " Or expand local context with read_chunks "
                (json/write-str {:doc_num (:doc-num range-hint)
                                 :chunk_range (:chunk-range range-hint)})
                "."))
         "]")))

(defn format-finalize-hint
  [decision]
  (str "[SYSTEM: Sufficiency gate passed. "
       (:reasoning decision)
       " Do not search or read more. Use generate_response now to finalize the answer from the evidence already in the workspace.]"))

(defn format-clarification-hint
  [decision]
  (str "[SYSTEM: Sufficiency gate rejected the evidence because the query is still ambiguous or off-topic. "
       (:reasoning decision)
       (when (seq (:missing-info decision))
         (str " Missing info: " (str/join "; " (:missing-info decision)) "."))
       " Ask the user a concise clarification question instead of continuing to search.]"))

(defn format-finalize-with-uncertainty-hint
  [decision]
  (str "[SYSTEM: Sufficiency gate cannot resolve the remaining gap within budget. "
       (:reasoning decision)
       (when (seq (:missing-info decision))
         (str " Missing info: " (str/join "; " (:missing-info decision)) "."))
       " Finalize with an explicit uncertainty or conflict explanation rather than continuing to search.]"))

(defn grounding-decision
  "Deterministic grounding floor, evaluated on the produced text rather than by
   an LLM.

   `:citation-validation` (from `synthesis/validate-citations-programmatically`)
   answers two different questions, and only one of them was ever consulted:

     :all-valid?  integrity — are the [N] markers the model emitted real?
     :grounded?   presence  — did it cite anything real AT ALL?

   An answer with no citations has no INVALID ones, so it satisfied
   `:all-valid?` trivially. Measured consequence: asked an off-corpus question,
   the agent searched, retrieved 8-11 chunks, found nothing relevant, and
   answered from model knowledge with zero citations — reproduced 5/5 on both
   gpt-4o and gpt-5.6-sol and on the pre-PR code, so this is a long-standing
   pipeline gap rather than a model trait or a regression.

   Returns nil when the gate does not apply (no answer generated yet) or when
   the answer is grounded; otherwise an insufficiency decision in the same shape
   `evaluate-sufficiency` produces, tagged `:source :grounding-gate` so the
   trace can tell it apart from the LLM gate.

   Deterministic on purpose: it cannot itself hallucinate, costs no tokens, and
   short-circuits the response-validation LLM call rather than adding one."
  [workspace]
  (when-let [cv (:citation-validation workspace)]
    (when-not (:grounded? cv)
      {:status :insufficient
       :source :grounding-gate
       :suggested-strategy :grounding
       :reasoning (str "The generated answer cites no retrieved source ("
                       (:total-references cv 0) " reference(s), "
                       (count (:valid-indices cv)) " valid), so nothing in it is "
                       "traceable to the knowledge base.")
       :missing-info ["a citation to a retrieved chunk supporting the answer"]})))

(defn format-grounding-hint
  "Hint for a `:grounding` rejection. Names BOTH permitted outcomes: cite, or
   say the knowledge base does not cover it. Offering only 'cite your sources'
   invites the model to re-assert the same ungrounded answer with a plausible
   marker attached, which the integrity check would then reject as invalid."
  [decision]
  (str "[SYSTEM: Grounding gate rejected the answer. "
       (:reasoning decision)
       " Do one of two things: (1) cite the specific retrieved chunks that "
       "support each claim, using their [N] markers; or (2) state plainly that "
       "the knowledge base does not cover this question. Do NOT answer from "
       "prior knowledge or memory — an uncited answer is indistinguishable to "
       "the reader from a sourced one.]"))
