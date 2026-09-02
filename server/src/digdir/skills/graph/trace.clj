(ns digdir.skills.graph.trace
  "Trace-file writer for graph-runner runs.

   Parallels digdir.skills.builtin.agent.core's write-trace-file! but
   captures graph-level execution: graph id, per-step skill/status/timing,
   input/output keys, full output content, and any error. Useful for
   diagnosing custom skill graphs (S2/S3/S5 demo scenarios and beyond),
   which previously had no on-disk record of their execution.

   Policy: never truncate model output. Synthesis responses, tool-call
   args, structured tool outputs, and prompts/parameters are written in
   full. Bulk retrieved-content vectors render shape header + every
   element via pprint — also full. Trace files may grow large; that's
   the point during development."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [digdir.rag.skills.core :as skills]))

(def ^:private trace-dir "logs")

(defn- pprint-str
  "pprint to a string. Used for maps, vectors, and other structured values
   that benefit from multi-line layout. Never truncates."
  [v]
  (with-out-str (pprint/pprint v)))

(defn- indent-block
  "Indent every line of a multi-line string by `prefix` so it nests cleanly
   under a `- key ::` bullet without losing markdown structure."
  [s prefix]
  (->> (str/split-lines s)
       (map #(str prefix %))
       (str/join "\n")))

(defn- render-value
  "Render a value in full for development tracing. Strings keep their raw
   markdown so a synthesis response stays readable; collections go through
   pprint. No char limits."
  [v]
  (cond
    (nil? v) "nil"
    (string? v) v
    (keyword? v) (pr-str v)
    (or (number? v) (boolean? v)) (str v)
    (or (map? v) (vector? v) (set? v) (sequential? v)) (str/trim-newline (pprint-str v))
    :else (pr-str v)))

(defn- append-labeled
  "Append a value under `[label]`. Multi-line content goes on its own indented
   block; single-line content stays inline. Both kinds end with a trailing
   newline so the next entry doesn't run on."
  [^StringBuilder sb label value]
  (let [rendered (render-value value)]
    (if (and (string? rendered) (str/includes? rendered "\n"))
      (do
        (.append sb (str "[" label "] |\n"))
        (.append sb (indent-block rendered "  "))
        (.append sb "\n"))
      (.append sb (str "[" label "] " rendered "\n")))))

(defn- append-bulleted
  "Append a value under `  - key ::`. Multi-line content nests under the
   bullet with extra indentation."
  [^StringBuilder sb key-name value]
  (let [rendered (render-value value)]
    (if (and (string? rendered) (str/includes? rendered "\n"))
      (do
        (.append sb (str "  - " key-name " ::\n"))
        (.append sb (indent-block rendered "      "))
        (.append sb "\n"))
      (.append sb (str "  - " key-name " :: " rendered "\n")))))

(defn- format-input-block
  [inputs]
  (let [sb (StringBuilder.)]
    (.append sb "== GRAPH INPUTS ==\n")
    (doseq [[k v] inputs]
      (append-labeled sb (name k) v))
    (.append sb "\n")
    (str sb)))

(defn- step-status
  "Distill the runner's step-timing + result into a single status keyword
   for the trace header line."
  [{:keys [status]} _result]
  (or status :ok))

(defn- foreach-step?*
  "Local predicate to avoid a require-cycle with digdir.skills.graph.schema —
   foreach steps are distinguished by the presence of a top-level :foreach key
   plus an inner :do block."
  [step-def]
  (and (map? step-def) (contains? step-def :foreach)))

(defn- format-iteration-line
  [{:keys [idx duration-ms status skipped? defaulted? error] :as _meta}]
  (let [tag (cond skipped?   "skip"
                  defaulted? "default"
                  :else      (name (or status :ok)))
        err-short (when error
                    (or (some-> error :error :error-message)
                        (some-> error :error pr-str)
                        (pr-str error)))]
    (str "  [" idx "] " tag
         (when duration-ms (str " " duration-ms "ms"))
         (when err-short (str " — error=" err-short)))))

(defn- loop-step?*
  "Local predicate (avoids require cycle with schema). Loop steps carry a
   :loop key at the top level."
  [step-def]
  (and (map? step-def) (contains? step-def :loop)))

(defn- sub-graph-step?*
  "Local predicate (avoids require cycle with schema). Sub-graph steps carry a
   :sub-graph key at the top level (not inside :do, which is the loop body's
   sub-graph case)."
  [step-def]
  (and (map? step-def) (contains? step-def :sub-graph) (not (contains? step-def :loop))))

(defn- select-step?*
  "Local predicate (avoids require cycle). Select steps carry a :select key."
  [step-def]
  (and (map? step-def) (contains? step-def :select)))

(defn- dispatch-by-name-step?*
  "Local predicate (avoids require cycle). Dispatch-by-name steps carry a
   :dispatch-by-name key."
  [step-def]
  (and (map? step-def) (contains? step-def :dispatch-by-name)))

(defn- format-step-block
  [{:keys [step-id step-def timing result]}]
  (let [sb (StringBuilder.)
        foreach? (foreach-step?* step-def)
        sub-graph? (sub-graph-step?* step-def)
        loop? (loop-step?* step-def)
        loop-inner-sub-graph? (and loop? (contains? (:do step-def) :sub-graph))
        skill-id (cond
                   loop?      (or (-> step-def :do :skill)
                                  (-> step-def :do :sub-graph :graph-id))
                   foreach?   (-> step-def :do :skill)
                   sub-graph? (-> step-def :sub-graph :graph-id)
                   :else      (:skill step-def))
        inputs-decl (cond
                      loop?      (if loop-inner-sub-graph?
                                   (-> step-def :do :sub-graph :inputs)
                                   (-> step-def :do :inputs))
                      foreach?   (-> step-def :do :inputs)
                      sub-graph? (-> step-def :sub-graph :inputs)
                      :else      (:inputs step-def))
        params-decl (cond
                      loop?    (-> step-def :do :parameters)
                      foreach? (-> step-def :do :parameters)
                      :else    (:parameters step-def))
        outputs (when (and result (skills/result-success? result))
                  (skills/get-result-outputs result))
        result-metadata (when result (skills/get-result-metadata result))
        iterations (when (or foreach? loop?) (:iterations result-metadata))
        error (when (and result (skills/result-error? result))
                result)
        status (step-status timing result)]
    (.append sb (str "== STEP " (name step-id) " ==\n"))
    (.append sb (str "[skill] " skill-id "\n"))
    (when foreach?
      (.append sb (str "[foreach] over=" (pr-str (-> step-def :foreach :over))
                       (when-let [as-k (-> step-def :foreach :as)] (str " as=" (pr-str as-k)))
                       (when-let [idx-k (-> step-def :foreach :as-index)] (str " as-index=" (pr-str idx-k)))
                       (when-let [coll-k (:collect-as step-def)] (str " collect-as=" (pr-str coll-k)))
                       "\n")))
    (when loop?
      (.append sb (str "[loop] max-iterations=" (-> step-def :loop :max-iterations)
                       (when-let [u (-> step-def :loop :until-output)] (str " until-output=" (pr-str u)))
                       (when-let [w (-> step-def :loop :while-output)] (str " while-output=" (pr-str w)))
                       (when-let [iter-k (-> step-def :loop :iteration-as)] (str " iteration-as=" (pr-str iter-k)))
                       (when-let [idx-k (-> step-def :loop :iteration-index-as)] (str " iteration-index-as=" (pr-str idx-k)))
                       (when-let [coll-k (:collect-as step-def)] (str " collect-as=" (pr-str coll-k)))
                       (when (some? (:exhausted? result-metadata))
                         (str " exhausted?=" (:exhausted? result-metadata)))
                       (when-let [br (:break-reason result-metadata)]
                         (str " break-reason=" (pr-str br)))
                       "\n")))
    (when sub-graph?
      (.append sb (str "[sub-graph] graph-id=" (pr-str (-> step-def :sub-graph :graph-id))
                       (when-let [child-id (:child-graph-id result-metadata)]
                         (str " child-graph-id=" (pr-str child-id)))
                       (when-let [steps (:child-step-count result-metadata)]
                         (str " child-step-count=" steps))
                       (when-let [cdur (:child-duration-ms result-metadata)]
                         (str " child-duration-ms=" cdur))
                       "\n")))
    (when (select-step?* step-def)
      (.append sb (str "[select] on=" (pr-str (-> step-def :select :on))
                       (when-let [dv (:dispatch-value result-metadata)]
                         (str " dispatch-value=" (pr-str dv)))
                       (when-let [sb-key (:selected-branch result-metadata)]
                         (str " selected-branch=" (pr-str sb-key)))
                       " branches=" (pr-str (vec (keys (:branches step-def))))
                       "\n")))
    (when (dispatch-by-name-step?* step-def)
      (.append sb (str "[dispatch-by-name] name-ref=" (pr-str (-> step-def :dispatch-by-name :name))
                       " registry-ref=" (pr-str (-> step-def :dispatch-by-name :registry))
                       (when-let [rn (:resolved-name result-metadata)]
                         (str " resolved-name=" (pr-str rn)))
                       (when-let [sk (:resolved-skill-id result-metadata)]
                         (str " resolved-skill-id=" (pr-str sk)))
                       "\n")))
    (.append sb (str "[status] " (name status) "\n"))
    (when (some? (:duration-ms timing))
      (.append sb (str "[duration-ms] " (:duration-ms timing) "\n")))
    (when (seq inputs-decl)
      (.append sb (str "[input-refs] "
                       (str/join ", " (map (fn [[k ref]] (str (name k) "=" (pr-str ref)))
                                           inputs-decl))
                       "\n")))
    (when (seq params-decl)
      (.append sb (str "[parameters] " (pr-str params-decl) "\n")))
    (when (seq iterations)
      (.append sb (str "[iterations] " (count iterations) "\n"))
      (doseq [im iterations]
        (.append sb (format-iteration-line im))
        (.append sb "\n")))
    (when error
      (.append sb (str "[error] " (pr-str (or (:error error) error)) "\n")))
    (when (seq outputs)
      (.append sb "[outputs]\n")
      (doseq [[k v] outputs]
        (append-bulleted sb (name k) v)))
    (.append sb "\n")
    (str sb)))

(defn- format-final-outputs
  [outputs]
  (let [sb (StringBuilder.)]
    (.append sb "== FINAL OUTPUTS ==\n")
    (if (seq outputs)
      (doseq [[k v] outputs]
        (append-labeled sb (name k) v))
      (.append sb "(none)\n"))
    (str sb)))

(defn- format-extra-section
  "Render an :extra-sections entry. Each entry is `{:title string :content string}`
   or `{:title string :lines [string]}`. The agent trace synthesizer (Phase 2.0)
   uses this to splice in cross-cutting blocks that aren't per-step (stage-timing
   rollups, search history, sufficiency decisions, citations, etc.)."
  [{:keys [title content lines]}]
  (let [sb (StringBuilder.)]
    (.append sb (str "== " (or title "EXTRA") " ==\n"))
    (cond
      content (do (.append sb content)
                  (when-not (str/ends-with? content "\n")
                    (.append sb "\n")))
      (seq lines) (doseq [l lines]
                    (.append sb (str l "\n")))
      :else (.append sb "(empty)\n"))
    (.append sb "\n")
    (str sb)))

(defn format-trace-sb
  "Render a graph-runner trace into a StringBuilder. Returning the SB (a
   CharSequence) rather than its `.toString` lets the caller stream it
   directly to a Writer via `.append`, avoiding a multi-MB string allocation
   for large traces."
  ^StringBuilder
  [{:keys [graph-id inputs opts run-status duration-ms step-defs step-timings
           step-results outputs error extra-sections]}]
  (let [sb (StringBuilder.)]
    (.append sb "== GRAPH RUNNER TRACE ==\n")
    (.append sb (str "[graph-id] " (or graph-id "unknown") "\n"))
    (.append sb (str "[timestamp] " (java.time.Instant/now) "\n"))
    (.append sb (str "[status] " (name (or run-status :ok)) "\n"))
    (when (some? duration-ms)
      (.append sb (str "[duration-ms] " duration-ms "\n")))
    (.append sb (str "[tenant] " (or (:tenant opts) "—") "\n"))
    (.append sb (str "[dataset-config-key] " (or (:dataset-config-key opts) "—") "\n"))
    (.append sb (str "[runtime-config-key] " (or (:runtime-config-key opts) "—") "\n"))
    (.append sb (str "[steps] " (count step-defs) "\n"))
    (.append sb "\n")
    (.append sb (format-input-block inputs))
    (doseq [step step-defs]
      (let [step-id (:id step)
            timing (get step-timings step-id)
            result (get step-results step-id)]
        (.append sb (format-step-block {:step-id step-id
                                        :step-def step
                                        :timing timing
                                        :result result}))))
    (when error
      (.append sb "== RUN ERROR ==\n")
      (.append sb (str (pr-str error) "\n\n")))
    (.append sb (format-final-outputs outputs))
    (doseq [section extra-sections]
      (.append sb "\n")
      (.append sb (format-extra-section section)))
    sb))

(defn format-trace
  "Render a graph-runner trace as ripgrep-friendly plain text. Pure fn —
   take it apart in tests if/when we want.

   Optional `:extra-sections` is a vector of `{:title :content}` (or `:lines`)
   maps appended after the final outputs."
  [trace-data]
  (.toString (format-trace-sb trace-data)))

(defonce ^:private trace-write-executor
  (java.util.concurrent.Executors/newSingleThreadExecutor
    (reify java.util.concurrent.ThreadFactory
      (newThread [_ r]
        (doto (Thread. ^Runnable r "graph-trace-writer")
          (.setDaemon true))))))

(defn- write-trace-file-sync!
  [{:keys [graph-id] :as trace-data}]
  (try
    (let [ts (-> (java.time.Instant/now) str (str/replace ":" "-") (str/replace "." "-"))
          slug (some-> graph-id name (str/replace #"[^a-zA-Z0-9_-]" "-"))
          filename (str "graph-trace-" (or slug "graph") "-" ts ".txt")
          file (io/file trace-dir filename)]
      (io/make-parents file)
      ;; Stream the StringBuilder (a CharSequence) directly into a buffered
      ;; writer. Avoids the multi-MB intermediate String allocation that
      ;; `(spit file (.toString sb))` would force.
      (with-open [w (io/writer file)]
        (.append w (format-trace-sb trace-data)))
      (.getPath file))
    (catch Exception e
      (println "Warning: Failed to write graph-runner trace file:" (.getMessage e))
      nil)))

(defn write-trace-file!
  "Write a graph-runner trace to a timestamped file under server/logs/, async.

   Submitted to a single-thread daemon executor so it never blocks the
   graph-runner's hot path. Returns the executor's Future; callers that
   don't need the file path can ignore it. Errors here must not fail the
   surrounding graph run — this is observability, not load-bearing logic."
  [trace-data]
  (.submit ^java.util.concurrent.ExecutorService trace-write-executor
           ^Callable (fn [] (write-trace-file-sync! trace-data))))
