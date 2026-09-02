(ns digdir.sweep.questions
  "Load and validate the sweep question fixture
   (`server/test/fixtures/sweep/questions.edn`).

   The sweep runner (Phase S1) calls `load-questions!` for the canonical
   list. Tests call `validate!` to gate on schema drift. Both code paths
   go through the same parser so a hand-edit that breaks the fixture
   fails fast in CI rather than silently producing bad leaderboards."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def default-fixture-path
  "Resolved relative to repo root. The :user.dir when running from
   `server/` is `…/harmonize-runtime/server`, so the fixture lives at
   `test/fixtures/sweep/questions.edn` from that root."
  "test/fixtures/sweep/questions.edn")

(def known-datasets
  #{:digdir :public-docs})

(def known-sources
  #{:baseline :exploratory :public-docs-smoke :altinn3-stability :new
    :exploratory-rerank})

(def known-grounding-modes
  #{:chunks+answer :answer-only :refusal-expected})

(def known-tags
  "Tag vocabulary, grouped by axis. The validator requires at least
   one :domain tag per row; :difficulty and :form tags are encouraged
   but not strictly required (some rows are tagged only by domain,
   which is fine for slicing). Adding a new tag here is the only
   safe way to extend the vocabulary — anything else is a typo.

   The :form axis carries the original `:lookup` value (kept for the
   first-cohort questions) plus the more specific query-family values
   borrowed from the rerank suite — :exact-lookup, :navigational,
   :factual, :paraphrase. Use the specific value when you can; fall
   back to :lookup for generic 'what does X say' questions where the
   finer distinction isn't meaningful."
  {:difficulty #{:simple :compound :temporal :compare :hard :negative}
   :form       #{:lookup :list :extraction :followup :open-ended
                 :exact-lookup :navigational :factual :paraphrase}
   :domain     #{:digdir-arsverk
                 :altinn-broker
                 :altinn-dialogporten
                 :altinn-studio
                 :altinn-3-general
                 :altinn-authorization
                 :altinn-events
                 :altinn-notifications
                 :altinn-correspondence
                 :altinn-systemuser}})

(def all-known-tags
  (apply set/union (vals known-tags)))

(defn load-raw
  "Read and parse the fixture EDN. Returns the whole top-level map
   (`{:version :created :notes :questions}`). Throws if the file is
   missing or unparseable."
  ([] (load-raw default-fixture-path))
  ([path]
   (let [file (io/file path)
         _ (when-not (.exists file)
             (throw (ex-info "Sweep fixture not found"
                             {:path path
                              :cwd (System/getProperty "user.dir")})))]
     (with-open [r (io/reader file)]
       (edn/read (java.io.PushbackReader. r))))))

(defn- compile-pattern
  "Compile an :expected-answer-pattern. Returns {:ok? true :pattern p}
   on success or {:ok? false :error msg} on regex failure."
  [s]
  (try
    {:ok? true :pattern (re-pattern s)}
    (catch java.util.regex.PatternSyntaxException e
      {:ok? false :error (.getMessage e)})))

(defn- validate-row
  "Validate a single question row. Returns a vector of error strings
   (empty when the row is clean)."
  [{:keys [id dataset query golden-chunk-ids expected-answer-pattern
           tags source grounding-mode reference-answer tombstone?]
    :as row}]
  (if tombstone?
    (if (string? id)
      []
      [(str "tombstone row missing :id: " (pr-str row))])
    (let [errs (volatile! [])
          push! (fn [msg] (vswap! errs conj msg))
          label (if (string? id) id (pr-str row))]
      (when-not (string? id)
        (push! (str "row " (pr-str row) ": :id must be a string")))
      (when (and (string? id) (str/blank? id))
        (push! ":id must be non-blank"))
      (when-not (contains? known-datasets dataset)
        (push! (str label ": :dataset must be one of "
                    (pr-str known-datasets) ", got " (pr-str dataset))))
      (when-not (string? query)
        (push! (str label ": :query must be a string")))
      (when (and (string? query) (str/blank? query))
        (push! (str label ": :query must be non-blank")))
      (when-not (vector? golden-chunk-ids)
        (push! (str label ": :golden-chunk-ids must be a vector (may be empty)")))
      (when (and (vector? golden-chunk-ids)
                 (not (every? string? golden-chunk-ids)))
        (push! (str label ": :golden-chunk-ids must contain only strings")))
      (when-not (string? expected-answer-pattern)
        (push! (str label ": :expected-answer-pattern must be a string")))
      (when (string? expected-answer-pattern)
        (let [{:keys [ok? error]} (compile-pattern expected-answer-pattern)]
          (when-not ok?
            (push! (str label ": :expected-answer-pattern is not a valid regex: "
                        error)))))
      (when-not (set? tags)
        (push! (str label ": :tags must be a set")))
      (when (and (set? tags) (empty? tags))
        (push! (str label ": :tags must be non-empty")))
      (when (and (set? tags) (not (every? all-known-tags tags)))
        (push! (str label ": :tags contains unknown values: "
                    (pr-str (set/difference tags all-known-tags)))))
      (when (and (set? tags)
                 (empty? (set/intersection tags (:domain known-tags))))
        (push! (str label ": :tags must include at least one :domain tag from "
                    (pr-str (:domain known-tags)))))
      (when-not (contains? known-sources source)
        (push! (str label ": :source must be one of "
                    (pr-str known-sources) ", got " (pr-str source))))
      (when (and (some? grounding-mode)
                 (not (contains? known-grounding-modes grounding-mode)))
        (push! (str label ": :grounding-mode must be one of "
                    (pr-str known-grounding-modes)
                    ", got " (pr-str grounding-mode))))
      ;; Optional LLM-as-judge reference answer (see
      ;; plans/proposed/llm-as-judge-answer-quality-plan.md). When present it
      ;; must be a non-blank string; references live in references.edn keyed by
      ;; :id, but an inline :reference-answer is also accepted.
      (when (and (some? reference-answer)
                 (or (not (string? reference-answer))
                     (str/blank? reference-answer)))
        (push! (str label ": :reference-answer, when present, must be a "
                    "non-blank string")))
      ;; If grounding-mode is :chunks+answer (or unset, which defaults
      ;; to :chunks+answer), :golden-chunk-ids must be non-empty.
      (when (and (or (nil? grounding-mode) (= :chunks+answer grounding-mode))
                 (vector? golden-chunk-ids)
                 (empty? golden-chunk-ids))
        (push! (str label ": :grounding-mode :chunks+answer requires "
                    ":golden-chunk-ids to be non-empty (or set "
                    ":grounding-mode :answer-only)")))
      @errs)))

(defn validate!
  "Validate the parsed fixture. Returns the fixture unchanged on success.
   Throws ex-info with :errors on any validation failure.

   Arity-0: load from `default-fixture-path` and validate.
   Arity-1: validate an already-loaded map."
  ([] (validate! (load-raw)))
  ([fixture]
   (let [{:keys [version questions]} fixture
         _ (when-not (= 1 version)
             (throw (ex-info "Unknown fixture version"
                             {:version version :expected 1})))
         _ (when-not (vector? questions)
             (throw (ex-info "Fixture :questions must be a vector"
                             {:got (type questions)})))
         live-rows (remove :tombstone? questions)
         ids (map :id live-rows)
         dup-ids (->> ids frequencies (filter #(> (val %) 1)) (map key))
         row-errors (mapcat validate-row live-rows)
         errors (cond-> (vec row-errors)
                  (seq dup-ids)
                  (conj (str "duplicate :id values: " (pr-str dup-ids))))]
     (when (seq errors)
       (throw (ex-info "Sweep question fixture failed validation"
                       {:errors errors
                        :error-count (count errors)})))
     fixture)))

(defn load-questions!
  "Production entry point. Loads and validates the fixture, returns
   the vector of live (non-tombstone) question rows. The sweep runner
   should call this — never `load-raw` directly — so schema drift
   trips on the first invocation rather than mid-run."
  ([] (load-questions! default-fixture-path))
  ([path]
   (let [fixture (validate! (load-raw path))]
     (->> (:questions fixture)
          (remove :tombstone?)
          vec))))

(defn summary
  "Quick human-readable summary of the current fixture. Useful from
   the REPL; not used by the runner."
  ([] (summary (load-questions!)))
  ([rows]
   {:total (count rows)
    :by-dataset (frequencies (map :dataset rows))
    :by-source (frequencies (map :source rows))
    :by-grounding-mode (frequencies (map #(or (:grounding-mode %)
                                              :chunks+answer)
                                         rows))
    :by-domain (->> rows
                    (mapcat (fn [r]
                              (set/intersection (:tags r)
                                                (:domain known-tags))))
                    frequencies)
    :total-golden-chunk-ids (count (mapcat :golden-chunk-ids rows))
    :unique-golden-chunk-ids (count (into #{} (mapcat :golden-chunk-ids rows)))}))

;; --- TODO (Phase S1 follow-up) -------------------------------------
;;
;; `validate-chunk-ids-resolve!` — for rows with non-empty
;; :golden-chunk-ids, hit the dev DB and confirm each id maps to a
;; live chunk. Stubbed out here because it requires a live DB
;; connection and a target dataset binding; we wire it up in S1 when
;; the runner already has both.
;;
;; (defn validate-chunk-ids-resolve!
;;   "Resolve every :golden-chunk-ids ref against the live dataset.
;;    Throws if any chunk-id is unknown."
;;   [conn dataset-key rows] ...)
