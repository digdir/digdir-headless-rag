(ns digdir.rag.auto-filter-rules
  "Rule engine for query-aware filter detection.

   Code in this namespace provides rule TYPES — pure dispatch by
   `:rule/type`. Corpus-specific RULE INSTANCES (which field, which
   marker words, which regex patterns) live in runtime config under
   `skills.retrieval.auto-filter-rules`. Adding a new rule type is a
   code change; adding a new rule instance for a corpus is a config
   change.

   Each rule handler returns either:
   - `{:fields [{:field … :type … :selected-options …} …]}` —
     contribution to the merged filter map, or
   - `nil` when the rule didn't match with enough confidence.

   The rule types implemented here cover the cases the v3 baseline
   identified as the largest underused signals on digdir/public-docs:
   - `:marker-word-classify` — classify the query against per-value
     marker-word sets (e.g. NB vs EN by function-word distribution).
   - `:shape-pattern` — first-match-wins regex-to-value mapping
     (e.g. question-shape → diataxis category).
   - `:llm-classify` — per-query LLM call that picks values from a
     universe. Used when heuristics are too brittle (e.g. \"Hva er
     forskjellen\" comparatives, Q3 cross-diataxis questions). Costs
     one LLM call per rule instance per retrieve.

   See plans/proposed/retrieval-configurable-fields-rules-plan.md
   and plans/proposed/retrieval-dynamic-filter-generation-experiment.md."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [litellm.core :as litellm]))

(defmulti detect-by-rule
  "Dispatch a single rule spec against a vector of query strings.
   Returns `{:fields […]}` on a confident match, or `nil` otherwise."
  (fn [rule _queries _opts] (:rule/type rule)))

(defmethod detect-by-rule :default
  [_rule _queries _opts]
  ;; Unknown rule type — log nothing, return nil so the engine can
  ;; gracefully skip. (Loud-failing here would block boot for any
  ;; stale tenant config; the runtime contract is that unknown rule
  ;; types are no-ops.)
  nil)

(defn- tokenize
  "Lower-case the input and split on non-letter/digit; return a set
   of non-blank tokens. Used by `:marker-word-classify`."
  [s]
  (when (seq (str s))
    (->> (str/split (str/lower-case (str s)) #"[^\p{L}\p{N}]+")
         (remove str/blank?)
         set)))

;; ---------------------------------------------------------------------------
;; :marker-word-classify
;; ---------------------------------------------------------------------------

(defn- normalize-classify-value-spec
  "A value spec is `{:marker-words [...] :requires-chars regex-or-string?}`.
   We accept marker-words as a vector or set; characters regex as a
   string (will be compiled) or already-compiled Pattern."
  [spec]
  {:marker-words (set (map str/lower-case (:marker-words spec [])))
   :requires-chars (some-> (:requires-chars spec)
                           (cond-> string? re-pattern))})

(defn- score-classify-values
  "For each value in the rule's :values map, compute (overlap, char-match).
   Returns a seq of `[value overlap-count char-match?]`."
  [values text-tokens text]
  (for [[value spec] values
        :let [norm (normalize-classify-value-spec spec)
              overlap (count (set/intersection text-tokens (:marker-words norm)))
              char-match? (boolean (and (:requires-chars norm)
                                        (re-find (:requires-chars norm) text)))]]
    [value overlap char-match?]))

(defmethod detect-by-rule :marker-word-classify
  [{:keys [field values min-tokens threshold-ratio]
    :or {min-tokens 3 threshold-ratio 2.0}
    :as _rule}
   queries
   _opts]
  (let [text (str/lower-case (str/join " " queries))
        tokens (tokenize text)]
    (when (and field values (>= (count tokens) min-tokens))
      (let [scored (score-classify-values values tokens text)
            ;; A required-chars match short-circuits to that value
            char-hit (some (fn [[value _overlap char-match?]]
                             (when char-match? value))
                           scored)]
        (if char-hit
          {:fields [{:type :multiselect
                     :field field
                     :selected-options #{(str char-hit)}
                     :value-type :string}]}
          ;; Otherwise pick the value with strictly highest overlap,
          ;; requiring a margin of `threshold-ratio` over the runner-up
          ;; and at least 2 absolute matches.
          (let [overlaps (sort-by second > scored)
                [top-value top-count _] (first overlaps)
                [_ runner-count _] (or (second overlaps) [nil 0 nil])]
            (when (and (>= top-count 2)
                       (>= top-count (* threshold-ratio (max 1 runner-count))))
              {:fields [{:type :multiselect
                         :field field
                         :selected-options #{(str top-value)}
                         :value-type :string}]})))))))

;; ---------------------------------------------------------------------------
;; :shape-pattern
;; ---------------------------------------------------------------------------

(defn- compile-shape-patterns
  "Coerce :patterns entries to use compiled regex. Each pattern is
   `{:value :regex}` where :regex is a string or already-compiled
   Pattern. Returns a vec of `[value compiled-regex]`."
  [patterns]
  (for [{:keys [value regex]} patterns]
    [value (cond-> regex (string? regex) re-pattern)]))

(defmethod detect-by-rule :shape-pattern
  [{:keys [field mode universe patterns]
    :or {mode :strict}
    :as _rule}
   queries
   _opts]
  (let [text (str/join " " queries)]
    (when (and field (seq patterns))
      (let [first-match (some (fn [[value re]]
                                (when (re-find re text) value))
                              (compile-shape-patterns patterns))]
        (cond
          (nil? first-match)
          nil

          (= mode :permissive)
          (let [complement (->> universe
                                (remove #(= % first-match))
                                vec)]
            (if (empty? complement)
              nil ; matching one value AND complement-empty would be a no-op
              {:fields [{:type :not-in-set
                         :field field
                         :selected-options (set (map str complement))
                         :value-type :string}]}))

          :else
          {:fields [{:type :multiselect
                     :field field
                     :selected-options #{(str first-match)}
                     :value-type :string}]})))))

;; ---------------------------------------------------------------------------
;; :llm-classify
;; ---------------------------------------------------------------------------

(defn- selected->filter-fields
  "Convert a vec of selected values to a filter `:fields` entry under
   `:strict` or `:permissive` semantics. Returns nil when the result
   wouldn't constrain anything."
  [field selected universe mode]
  (let [selected (vec (distinct (map str selected)))
        universe-set (set (map str universe))
        valid (filterv #(contains? universe-set %) selected)]
    (cond
      (empty? valid) nil

      (= mode :permissive)
      (let [complement (->> universe
                            (map str)
                            (remove (set valid))
                            vec)]
        (when (seq complement)
          [{:type :not-in-set
            :field field
            :selected-options (set complement)
            :value-type :string}]))

      :else
      [{:type :multiselect
        :field field
        :selected-options (set valid)
        :value-type :string}])))

(defn- render-llm-classify-prompt
  "Substitute {{field}}, {{universe-joined}}, and {{query}} into the
   rule's prompt template. The template is the experiment-defining
   piece; it lives in config so prompts can be iterated without code
   changes."
  [template field universe queries]
  (let [query-text (str/join " " queries)
        universe-joined (str/join ", " (map str universe))]
    (-> template
        (str/replace "{{field}}" (str field))
        (str/replace "{{universe-joined}}" universe-joined)
        (str/replace "{{query}}" query-text))))

(def ^:private llm-classify-tool-definition
  "OpenAI tool-schema for the classifier. The `:values` array is the
   only output; the enum constraint is filled in per-call based on
   the rule's universe."
  {:type "function"
   :function {:name "classify"
              :description "Pick zero or more values from the universe."
              :parameters {:type "object"
                           :properties {:values {:type "array"
                                                 :items {:type "string"}}}
                           :required ["values"]}}})

(defn- build-classify-tool
  "Compose the tool spec with an enum-constrained items schema so the
   LLM can only return values from the universe."
  [universe]
  (-> llm-classify-tool-definition
      (assoc-in [:function :parameters :properties :values :items :enum]
                (vec (map str universe)))))

(defn llm-classify-call
  "Make the LLM call for the `:llm-classify` rule type. Public so tests
   can `with-redefs` it without touching the engine internals.

   Returns a vec of selected values (possibly empty), or nil on
   network/parse error so the caller can no-op cleanly."
  [{:keys [tenant model temperature universe rendered-prompt]
    :or {temperature 0.0}}]
  (try
    (let [deployment (or model
                         (cfg/get {:tenant tenant} :services :azure-openai :deployment-name))
          response (litellm/completion
                    :azure-openai deployment
                    {:messages [{:role :user :content rendered-prompt}]
                     :tools [(build-classify-tool universe)]
                     :tool-choice :required
                     :temperature temperature}
                    {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
                     :api-base (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
                     :api-version (cfg/get {:tenant tenant} :services :azure-openai :api-version)
                     :deployment deployment})
          tool-call (-> response :choices first :message :tool-calls first)
          args-json (-> tool-call :function :arguments)]
      (when args-json
        (let [parsed (json/read-str args-json :key-fn keyword)
              values (:values parsed)]
          (when (sequential? values)
            (vec values)))))
    (catch Exception e
      (println "llm-classify-call failed:" (.getMessage e))
      nil)))

(defmethod detect-by-rule :llm-classify
  [{:keys [field universe mode model temperature prompt-template
           allow-multi-value]
    :or {mode :strict
         temperature 0.0
         allow-multi-value true}
    :as _rule}
   queries
   opts]
  (when (and field
             (seq universe)
             (not (str/blank? prompt-template))
             (seq queries)
             (:tenant opts))
    (let [rendered (render-llm-classify-prompt prompt-template field universe queries)
          raw-selected (or (llm-classify-call {:tenant (:tenant opts)
                                               :model model
                                               :temperature temperature
                                               :universe universe
                                               :rendered-prompt rendered})
                           [])
          ;; Honor allow-multi-value by truncating to first value.
          selected (cond->> raw-selected
                     (not allow-multi-value) (take 1)
                     true vec)]
      (cond
        ;; LLM returned nothing
        (empty? selected)
        nil

        ;; Filter-only-fields output
        :else
        (when-let [fields (selected->filter-fields field selected universe mode)]
          {:fields fields})))))

;; allow-none is informational (controls prompt phrasing); the rule
;; handler already treats `[]` from the LLM as no-filter regardless,
;; so it doesn't need a code branch.

;; ---------------------------------------------------------------------------
;; Engine entrypoint
;; ---------------------------------------------------------------------------

(defn detect-from-rules
  "Run a sequence of rule specs against the queries and merge their
   :fields contributions. Returns `{:fields [...]}` or nil. Rules are
   processed in order; same-field collisions are last-wins (caller can
   reorder rules in config to control precedence)."
  [rules queries opts]
  (let [results (->> (or rules [])
                     (keep #(detect-by-rule % queries opts))
                     (mapcat :fields))]
    (when (seq results)
      ;; Merge by :field, last-wins (matches how `merge-filter-by`
      ;; downstream handles explicit-vs-auto collisions).
      (let [by-field (reduce (fn [acc spec]
                               (assoc acc (:field spec) spec))
                             {}
                             results)]
        {:fields (vec (vals by-field))}))))
