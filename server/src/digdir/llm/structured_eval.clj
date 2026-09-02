(ns digdir.llm.structured-eval
  "Shared helpers for structured-output LLM evaluations.

   Several skills (read_signals, sufficiency, future classifiers) all follow the
   same pattern: build a strict system+user prompt, call the LLM at a low
   temperature, parse the JSON response, validate enum-valued fields, and fall
   back conservatively on any failure. Consolidated here so:

   - Case/underscore enum variants (e.g. \"Support_Found\", \"SUFFICIENT\") never
     silently force a degraded fallback.
   - Markdown code fences around the JSON are tolerated.
   - Exceptions are logged once, with the LLM error message preserved, so
     traces can distinguish LLM failures from semantic downgrades.
   - Every caller returns a value marked with :degraded? so downstream logic
     can tell a real LLM verdict from a heuristic fallback."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

;; =============================================================================
;; JSON parsing
;; =============================================================================

(defn strip-code-fences
  "Remove surrounding ```json / ``` fences that some LLMs add around JSON output."
  [text]
  (let [trimmed (str/trim (or text ""))]
    (if-let [[_ fenced] (re-matches #"(?s)```(?:json)?\s*(.*?)\s*```" trimmed)]
      fenced
      trimmed)))

(defn extract-json-object
  "Pull the first {...} substring out of `text`, after stripping markdown fences.
   Falls back to the cleaned string when no object delimiter is found, so the
   downstream reader can produce its own parse error."
  [text]
  (let [cleaned (strip-code-fences text)]
    (or (some->> (re-find #"(?s)\{.*\}" cleaned) str)
        cleaned)))

(defn parse-json-response
  "Parse an LLM response body that is expected to be a JSON object, tolerating
   code-fence wrapping. Returns keywordized keys.

   Throws ex-info with :stage :json-parse on failure."
  [text]
  (try
    (json/read-str (extract-json-object text) :key-fn keyword)
    (catch Exception e
      (throw (ex-info "Failed to parse LLM JSON response"
                      {:stage :json-parse
                       :raw text
                       :cause (.getMessage e)})))))

;; =============================================================================
;; Enum normalization + validation
;; =============================================================================

(defn keyword-like
  "Coerce a string/keyword to a normalized keyword. Lower-cases, trims, and
   maps underscores to hyphens so a single LLM casing variant (e.g.
   \"Support_Found\") doesn't break enum validation."
  [value]
  (let [raw (cond
              (keyword? value) (name value)
              (string? value) value
              :else nil)]
    (when raw
      (-> raw
          str/trim
          str/lower-case
          (str/replace \_ \-)
          keyword))))

(defn ->validated-enum
  "Normalize `value` via `keyword-like` and assert it belongs to `allowed-set`.
   Throws ex-info with :stage :enum-validation so callers can distinguish
   validation errors from JSON-parse errors."
  [value allowed-set field-name]
  (let [candidate (keyword-like value)]
    (when-not (contains? allowed-set candidate)
      (throw (ex-info (str "Invalid " field-name)
                      {:stage :enum-validation
                       :field field-name
                       :value value
                       :allowed allowed-set})))
    candidate))

(defn resolve-alias-or-normalize
  "Two-step normalization for enums that have semantic aliases beyond casing.

   `alias-map` maps known raw strings/keywords to canonical keywords
   (e.g. {:enough :sufficient, \"conflict\" :conflicting}). Anything not in
   the alias map falls back to `keyword-like`.

   Unlike `->validated-enum`, this does not throw on unknown values — callers
   that want strict validation should compose it with membership checks."
  [value alias-map]
  (or (get alias-map value)
      (when (keyword? value) (get alias-map (name value)))
      (keyword-like value)))

;; =============================================================================
;; Confidence
;; =============================================================================

(defn clamp-confidence
  "Coerce `value` to a double and clamp to [0.0, 1.0]. Throws ex-info on a
   value that can't be coerced."
  [value]
  (let [numeric (cond
                  (number? value) (double value)
                  (string? value) (try (Double/parseDouble value)
                                       (catch Exception _
                                         (throw (ex-info "Invalid confidence"
                                                         {:stage :confidence-parse
                                                          :value value}))))
                  :else (throw (ex-info "Invalid confidence"
                                        {:stage :confidence-parse
                                         :value value})))]
    (-> numeric (max 0.0) (min 1.0))))

;; =============================================================================
;; Evaluation envelope
;; =============================================================================

(defn- log-warning!
  [label e]
  (println (str "Warning: " label " failed: "
                (.getMessage e)
                (when-let [stage (:stage (ex-data e))]
                  (str " (stage " (name stage) ")")))))

(defn evaluate
  "Run a structured-output LLM evaluation.

   Arguments:
     :label         - Short human name for log lines (required).
     :llm-fn        - 4-arg chat-completion stub:
                      (fn [messages tools model temperature] -> response) (required).
     :system-prompt - System prompt string (required).
     :user-prompt   - User prompt string (required).
     :normalize-fn  - (fn [parsed-json] -> result) that validates + normalizes
                      the parsed JSON into a structured result map. Receives the
                      keywordized parse output from `parse-json-response`.
     :fallback-fn   - (fn [{:keys [exception stage]}] -> result) invoked on any
                      failure (LLM exception, JSON parse, enum validation).
                      Should return a result with :degraded? true.
     :model         - Optional model override.
     :temperature   - Optional temperature (default 0.0).

   Returns whatever normalize-fn or fallback-fn returns. This envelope adds no
   implicit state to the result — it's up to normalize-fn/fallback-fn to set
   :degraded? appropriately."
  [{:keys [label llm-fn system-prompt user-prompt normalize-fn fallback-fn
           model temperature]}]
  (try
    (-> (llm-fn [{:role "system" :content system-prompt}
                 {:role "user" :content user-prompt}]
                nil
                model
                (or temperature 0.0))
        (get-in [:choices 0 :message :content])
        parse-json-response
        normalize-fn)
    (catch Exception e
      (log-warning! label e)
      (fallback-fn {:exception e
                    :stage (:stage (ex-data e) :llm-call)}))))
