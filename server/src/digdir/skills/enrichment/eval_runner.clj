(ns digdir.skills.enrichment.eval-runner
  "Production-safe agent comparison runs, scored into the five-key row
   contract `digdir.skills.enrichment.batch-verdict` consumes.

   Slice 2a of #82 (#94). This exists because the evaluation path reached
   `digdir.sweep.runner/run-matrix` — 1,335 lines of research harness that
   is not on the production classpath, so the call threw
   FileNotFoundException the moment a user asked for an eval.

   What `run-matrix` was actually being asked for is much narrower than what
   it does. The enrichment eval passes `{configs questions repeats
   execution-scope}` and reads only `:rows`; the verdict then reads five keys
   off each row. Everything else run-matrix provides — CSV persistence,
   matrix provenance, resumable writes, concurrency, judging, model
   metadata, the Filter-4 stage decomposition — is sweep infrastructure that
   an accept-or-revert decision never touches. So this reimplements the
   narrow contract against `digdir.skills.api/run-skill-graph` rather than
   promoting the harness.

   DELIBERATE DIFFERENCE FROM THE HARNESS, and it matters if you compare
   numbers: `run-matrix` drives each question through
   `digdir.sweep.invoke/invoke-with-clarification-loop`, which answers the
   agent's clarifying questions with a simulated user. That is a research
   device for making sweeps comparable, and it pulls in
   `digdir.sweep.user-simulator`. This runner invokes the graph directly and
   does not simulate a user, so a question whose agent asks for
   clarification will score differently here than in a sweep. For the
   enrichment decision that is defensible — the comparison is
   enrichment-off vs enrichment-on over the same retrieval, and both arms
   are affected identically — but the absolute numbers are NOT comparable to
   historical sweep results. Whether a production eval should simulate
   clarification at all is a genuine product question, left to slice 2b.

   Scoring helpers are reimplemented from `digdir.sweep.runner` rather than
   shared, because sharing would require the harness namespace to be
   loadable — the exact dependency being removed. They are small and their
   semantics are pinned by tests."
  (:require [clojure.string :as str]
            [digdir.skills.api :as skills-api]))

(def ^:private chunk-id-keys
  [:chunk-id :chunk_id :id :uid :external-id])

(defn- chunk->id
  "Best-effort extraction of a stable chunk-id from a retrieval result chunk.
   Returns nil if none of the known keys carry a string value."
  [chunk]
  (some #(let [v (get chunk %)] (when (string? v) v)) chunk-id-keys))

(defn retrieved-chunk-ids
  "Ordered, deduplicated chunk-ids from a graph result.

   For the agent-rag graph variants the rank-ordered retrieval list lives at
   `[:diagnostics :outputs :workspace-final :reranked-chunks]`. Rank order is
   what recall@k is measured against, so that is read first; `:chunks` and
   the workspace chunk map are fallbacks for graphs that populate those
   instead."
  [result]
  (let [reranked (get-in result [:diagnostics :outputs :workspace-final :reranked-chunks])
        top-chunks (:chunks result)
        ws-chunks-map (get-in result [:diagnostics :outputs :workspace-final :chunks])]
    (->> (cond
           (seq reranked) reranked
           (seq top-chunks) top-chunks
           (map? ws-chunks-map) (map (fn [[k _]] {:chunk_id k}) ws-chunks-map)
           :else [])
         (keep chunk->id)
         distinct
         vec)))

(defn recall-at-k
  "Fraction of `expected` ids appearing in the first `k` of `retrieved`.
   nil when `expected` is empty — there is nothing to score against."
  [expected retrieved k]
  (when (seq expected)
    (let [head (set (take k retrieved))
          hits (count (filter head expected))]
      (/ (double hits) (count expected)))))

(defn answer-substring-hit?
  "True if `response` matches the question's `:expected-answer-pattern`."
  [pattern response]
  (boolean
    (when (and (string? pattern) (string? response) (seq response))
      (re-find (re-pattern pattern) response))))

(defn- response-text
  "The agent's answer text, wherever the graph surfaced it."
  [result]
  (or (:response result)
      (get-in result [:outputs :workspace-final :response])
      (get-in result [:diagnostics :outputs :workspace-final :response])))

(defn score-row
  "Score one (config, question, result) triple into the row contract
   `batch-verdict/compute-batch-verdict` reads.

   Pure — separated from execution so it can be tested without invoking an
   agent, which is the whole reason the harness version was untestable here."
  [config question result]
  (let [retrieved (retrieved-chunk-ids result)
        expected (:golden-chunk-ids question)]
    {:question-id (:id question)
     :config-id (:id config)
     :retrieved-chunk-ids (str/join ";" retrieved)
     :recall-at-20 (recall-at-k expected retrieved 20)
     ;; NOTE the trailing `?`. `compute-batch-verdict` reads
     ;; :answer-substring-hit? — emitting :answer-substring-hit would make
     ;; every answer-drop check silently see nil and never veto a keep.
     :answer-substring-hit? (answer-substring-hit?
                              (:expected-answer-pattern question)
                              (response-text result))
     ;; Read by the admin dashboard's re-run panel.
     :response (response-text result)
     :status (if (:error result) :error :ok)}))

(defn run-comparison
  "Run `{configs × questions × repeats}` and return `{:rows [...]}`.

   `configs`  — vec of `{:id :skill-graph-id :skill-params}`
   `questions` — vec of question rows carrying at least `:id`, `:query`,
                 `:golden-chunk-ids`, optionally `:expected-answer-pattern`
   `execution-scope` — `{:tenant :dataset-config-key :agent-id}`

   Serial by design: an accept-or-revert decision runs a handful of
   questions, and serial execution keeps failure attribution simple. A run
   that throws yields a row with nil metrics rather than aborting the batch,
   matching the outer graph's `:on-error :default` intent — one bad question
   should not discard the verdict for the rest."
  [{:keys [configs questions repeats execution-scope]
    :or {repeats 1}}]
  (when (empty? configs) (throw (ex-info "run-comparison: :configs is empty" {})))
  (when (empty? questions) (throw (ex-info "run-comparison: :questions is empty" {})))
  {:rows
   (vec
     (for [config configs
           question questions
           _ (range (max 1 (long repeats)))]
       (let [opts (cond-> {:tenant (:tenant execution-scope)
                           :dataset-config-key (:dataset-config-key execution-scope)
                           :skill-params (:skill-params config)}
                    (:agent-id execution-scope) (assoc :agent-id (:agent-id execution-scope)))
             result (try
                      (skills-api/run-skill-graph
                        (:skill-graph-id config)
                        {:query (:query question)}
                        opts)
                      (catch Throwable t
                        {:error (.getMessage t)}))]
         (cond-> (score-row config question result)
           (:error result) (assoc :error (:error result))))))})
