(ns digdir.sweep.dashboard
  "Live sweep-results dashboard for the admin UI.

   Reads the newest `server/results/sweep-*/runs.csv` and shows run progress,
   aggregate recall, and a per-row table. Polls every 2s via `e/System-time-secs`
   but only re-parses the CSV when its mtime changes (cheap when idle, live when
   the sweep writes rows)."
  {:clj-kondo/ignore true}
  (:require [hyperfiddle.electric3 :as e]
            [hyperfiddle.electric-dom3 :as dom]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.edn :as edn])
            #?(:clj [clojure.pprint :as pp])
            #?(:clj [clojure.string :as str])
            #?(:clj [digdir.config.db :as config-db])
            #?(:clj [digdir.config.core :as config-core])
            #?(:clj [digdir.playground.core :as playground])
            #?(:clj [digdir.skills.enrichment.naming :as enr-naming])
            [digdir.eval.run-validity :as run-validity]
            [digdir.eval.answer-language :as answer-language]
            #?(:clj [digdir.sweep.judge :as judge])))

;; =============================================================================
;; Server: read + aggregate the latest runs.csv
;; =============================================================================

#?(:clj
   (defn- parse-csv
     "Minimal RFC-4180 parser → vector of row-vectors. Handles quoted fields with
      embedded commas/newlines and doubled-quote escapes (the runs.csv :response
      column spans multiple physical lines)."
     [^String s]
     (let [n (count s)]
       (loop [i 0, fb (StringBuilder.), row (transient []), rows (transient []), q false]
         (if (>= i n)
           (persistent! (conj! rows (persistent! (conj! row (.toString fb)))))
           (let [c (.charAt s i)]
             (cond
               q (if (= c \")
                   (if (and (< (inc i) n) (= (.charAt s (inc i)) \"))
                     (do (.append fb \") (recur (+ i 2) fb row rows true))
                     (recur (inc i) fb row rows false))
                   (do (.append fb c) (recur (inc i) fb row rows true)))
               (= c \") (recur (inc i) fb row rows true)
               (= c \,) (recur (inc i) (StringBuilder.) (conj! row (.toString fb)) rows false)
               (= c \newline) (recur (inc i) (StringBuilder.) (transient [])
                                     (conj! rows (persistent! (conj! row (.toString fb)))) false)
               (= c \return) (recur (inc i) fb row rows false)
               :else (do (.append fb c) (recur (inc i) fb row rows false)))))))))

#?(:clj
   (defn- latest-csv ^java.io.File []
     (let [d (io/file "results")]
       (when (.isDirectory d)
         (some->> (.listFiles d)
                  (filter #(and (.isDirectory ^java.io.File %)
                                (str/starts-with? (.getName ^java.io.File %) "sweep-")))
                  seq
                  (sort-by #(.getName ^java.io.File %))
                  last
                  (#(io/file ^java.io.File % "runs.csv")))))))

#?(:clj (defn- ->num [s] (try (Double/parseDouble s) (catch Exception _ nil))))
#?(:clj (defn- pct [x] (str (Math/round (* 100.0 (double (or x 0)))) "%")))
#?(:clj (defn- fmt [x] (format "%.2f" (double (or x 0)))))

#?(:clj
   (def ^:private display-cols
     [:question-id :config-id :status :recall-at-10 :recall-at-20
      :golden-in-search-pool? :golden-in-display? :golden-read?
      :answer-substring-hit? :search-passes :enrichment-hits
      :reasoning-tokens :finish-reason :length-finish? :empty? :run-id]))

#?(:clj
   (defn- parse-rows
     "Full per-run maps (all columns, incl :response) from a runs.csv file."
     [^java.io.File f]
     (let [rows0 (remove #(= % [""]) (parse-csv (slurp f)))
           header (mapv keyword (first rows0))]
       (mapv #(zipmap header %) (rest rows0)))))

#?(:clj
   (defn- summarize [data]
     ;; Rates/means are computed over COMPLETE runs only, so error/timeout runs
     ;; (e.g. from a network blip) don't silently drag the numbers down. The
     ;; :errors count surfaces them instead.
     (let [status-complete (filter #(= "complete" (:status %)) data)
           ;; A run that reached no LLM is not a bad result, it is NOT A
           ;; RESULT (#276). #275 produced 16 of them, every one recorded
           ;; :complete with recall 0.0 — averaging those zeros in would have
           ;; reported a model that answered everything wrong, which is the
           ;; opposite conclusion from the true one. Excluded from every rate
           ;; and mean below, and counted separately so the exclusion is
           ;; visible rather than silent.
           never-ran (filterv run-validity/never-ran? status-complete)
           ;; #289: an answer in the wrong language is a BROKEN RUN, not a bad
           ;; answer — a fluent, correct, well-cited answer in the wrong
           ;; language would otherwise score on its merits against the
           ;; reference. Excluded for the same reason as never-ran, and
           ;; counted separately so the exclusion is visible.
           wrong-language (filterv answer-language/wrong-language? status-complete)
           complete (into [] (remove #(or (run-validity/never-ran? %)
                                          (answer-language/wrong-language? %)))
                          status-complete)
           n (count complete)
           errors (- (count data) (count status-complete))
           ;; judge failures (transient/infra): the agent run completed but the
           ;; judge call timed out / errored / returned unparseable output.
           judge-errors (count (filter #(#{"timeout" "error" "unparseable"}
                                         (:answer-judge-verdict %)) complete))
           ;; silent agent failure: status=complete but the agent emitted an
           ;; empty response (judge is correctly skipped → blank verdict). Caught
           ;; by neither Errors (status complete) nor Judge-err (verdict blank).
           no-answer (count (filter #(str/blank? (:response %)) complete))
           frac (fn [k] (if (pos? n) (/ (count (filter #(= "true" (k %)) complete)) (double n)) 0.0))
           mean (fn [k] (let [vs (keep #(->num (k %)) complete)]
                          (if (seq vs) (/ (reduce + vs) (count vs)) 0.0)))
           ;; Local-model signals (Part A). Reasoning-tokens is blank when no
           ;; usage came back, so mean over runs that actually carry a value;
           ;; length-finish?/empty? are token-cap / silent-cut-off detectors.
           reasoning-vals (keep #(->num (:reasoning-tokens %)) complete)
           mean-reasoning (if (seq reasoning-vals)
                            (/ (reduce + reasoning-vals) (count reasoning-vals)) 0.0)
           finish-hist (frequencies (keep #(not-empty (:finish-reason %)) complete))]
       {:answer-rate (pct (frac :answer-substring-hit?))
        :pool-rate (pct (frac :golden-in-search-pool?))
        :display-rate (pct (frac :golden-in-display?))
        :read-rate (pct (frac :golden-read?))
        :recall10 (fmt (mean :recall-at-10))
        :recall20 (fmt (mean :recall-at-20))
        :errors errors
        :never-ran (count never-ran)
        :wrong-language (count wrong-language)
        :judge-errors judge-errors
        :no-answer no-answer
        :reasoning-tokens (Math/round (double mean-reasoning))
        :length-finish-rate (pct (frac :length-finish?))
        :empty-rate (pct (frac :empty?))
        :finish-hist finish-hist})))

#?(:clj (def ^:private !cache (atom {})))

#?(:clj
   (defn- csv-snapshot
     "Mtime-cached snapshot of a runs.csv: {:rows (full maps) :dir :n :summary}.
      Re-parses only when the file changes; reused by both the results table and
      the reference-review join."
     [^java.io.File f]
     (let [ck [(.getPath f) (.lastModified f)]]
       (or (get @!cache ck)
           (let [rows (parse-rows f)
                 snap {:rows rows
                       :dir (.getName (.getParentFile f))
                       :n (count rows)
                       :summary (summarize rows)}]
             (reset! !cache {ck snap})
             snap)))))

#?(:clj
   (defn read-sweep
     "Aggregate + display-trimmed rows for the results table. `_tick` only forces
      Electric to re-invoke; the mtime cache skips re-parsing when unchanged."
     [_tick]
     (let [f (latest-csv)]
       (if (and f (.exists f))
         (let [{:keys [rows dir n summary]} (csv-snapshot f)]
           {:dir dir :n n :summary summary
            :rows (mapv #(select-keys % display-cols) rows)})
         {:dir "—" :n 0 :summary {} :rows []}))))

#?(:clj (defn- read-edn-file [path]
          (let [f (io/file path)]
            (when (.exists f) (try (edn/read-string (slurp f)) (catch Exception _ nil))))))

#?(:clj
   (defn read-config-info
     "Agent + per-config effective skill-params for the latest sweep, read from
      matrix.edn — so the dashboard shows exactly which agent + config was under
      test (the faithful-agent effective params, not just the matrix delta)."
     [_tick]
     (when-let [f (latest-csv)]
       (when-let [m (read-edn-file (str (.getParentFile f) "/matrix.edn"))]
         {:agent-id (or (:agent-id m) (get-in m [:execution-scope :agent-id]))
          :configs (mapv (fn [c]
                           {:id (:id c)
                            :skill-graph-id (str (:skill-graph-id c))
                            :effective (pr-str (or (:effective-skill-params c)
                                                   (:skill-params c)))})
                         (:configs m))}))))

#?(:clj
   (defn read-model-manifest
     "Compact view of the latest sweep's `<dir>/models.edn` (Part A model
      manifest), written once per sweep by the runner. Surfaces the agent
      model + quant + engine + mode, the key sampling env knobs, and the
      judge / generation models — so a screen cell is interpretable from the
      record alone.

      Best-effort: returns nil for sweeps predating the manifest (no
      models.edn) and NEVER throws — a legacy manifest carrying an
      unreadable `#sorted/map` env tag degrades to nil rather than crashing
      the view (the reader catches it). `_tick` only forces re-invoke."
     [_tick]
     (when-let [f (latest-csv)]
       (when-let [m (read-edn-file (str (.getParentFile ^java.io.File f) "/models.edn"))]
         (let [agent (get-in m [:tasks :agent])
               lm (:lmstudio agent)]
           {:captured-at (:captured-at m)
            ;; agent: the one whose sampling/reasoning varied silently today
            :agent {:model (:model agent)
                    :provider (some-> (:provider agent) name)
                    :engine (some-> (:engine agent) name)
                    :mode (some-> (:mode agent) name)
                    :reasoning-effort (some-> (:reasoning-effort agent) name)
                    :quantization (:quantization lm)
                    :arch (:arch lm)
                    :context (:loaded_context_length lm)
                    :state (:state lm)}
            :judge {:model (get-in m [:tasks :judge :model])
                    :provider (some-> (get-in m [:tasks :judge :provider]) name)
                    :note (get-in m [:tasks :judge :note])}
            :generation {:model (get-in m [:tasks :generation :model])
                         :mode (some-> (get-in m [:tasks :generation :mode]) name)}
            ;; sampling snapshot, sorted for a stable cell order
            :env (vec (sort-by key (or (:env m) {})))})))))

(def ^:private references-path "test/fixtures/sweep/references.edn")
(def ^:private questions-path "test/fixtures/sweep/questions.edn")

#?(:clj
   (defn- load-question-rows
     "Live question rows from the fixture this dashboard already reads for its
      review tables.

      Replaces `requiring-resolve` of `digdir.sweep.questions/load-questions!`
      (slice 2a of #94): that namespace is src-dev-only, so the call threw
      FileNotFoundException in a production build the moment a user clicked
      re-run. This reads the same file the dashboard reads elsewhere and drops
      tombstones, matching what load-questions! returned.

      KNOWN LIMITATION, and it is a data problem rather than a code one:
      `questions-path` is under `test/`, which `server.Dockerfile` does not
      copy (it ships src, src-build, src-prod, resources, config). So this
      resolves in a production build but finds no file. Giving the eval
      surface a real, user-supplied question source is slice 2b."
     []
     (->> (:questions (read-edn-file questions-path))
          (remove :tombstone?)
          vec)))
(def ^:private review-scope {:tenant "digdir" :dataset-config-key "default"})

;; In-memory overlays keyed by question-id, populated by the dashboard actions.
#?(:clj (def ^:private !judge-results (atom {})))   ; id -> {:verdict :score :rationale :judge-model :at}
#?(:clj (def ^:private !rerun-results (atom {})))   ; id -> {:response :recall-at-20 :answer-substring-hit? :at}
#?(:clj (def ^:private !experiment-results (atom {}))) ; id -> {:off {...} :on {...} :enrichment-collection :at}
#?(:clj (def ^:private !collections (atom nil)))
#?(:clj (def ^:private !chunk-cache (atom {})))

#?(:clj
   (defn- collections
     "Resolve {:chunks :docs} collection-name strings for the sweep dataset."
     []
     (or @!collections
         (try
           (let [dc (config-db/get-dataset-by-ref @(config-db/get-conn)
                                                  review-scope (config-core/get-master-key))]
             (reset! !collections {:chunks (:chunks-collection dc) :docs (:docs-collection dc)}))
           (catch Exception _ {:chunks nil :docs nil})))))

#?(:clj
   (defn- fetch-golden
     "Fetch + cache a golden chunk's display content by chunk-id."
     [chunk-id]
     (or (get @!chunk-cache chunk-id)
         (let [{:keys [chunks docs]} (collections)
               c (when (and chunks chunk-id)
                   (playground/fetch-chunk-by-id chunks docs chunk-id review-scope))
               v {:chunk-id chunk-id
                  :doc-num (:doc_num c)
                  :title (or (get-in c [:metadata :title]) (:title c))
                  :url (:url c)
                  :content (:content_markdown c)}]
           (when c (swap! !chunk-cache assoc chunk-id v))
           v))))

#?(:clj
   (defn read-review
     "Join reference answers with each question's query + golden chunks + the
      latest sweep's agent responses/recall, plus any on-demand judge / re-run
      overlays, for the dashboard's reference-review surface. Returns nil when no
      references file exists."
     [_tick]
     (when-let [refs (read-edn-file references-path)]
       (let [qmap (into {} (for [row (:questions (read-edn-file questions-path))] [(:id row) row]))
             f (latest-csv)
             rows (when (and f (.exists f)) (:rows (csv-snapshot f)))
             by-q (group-by :question-id rows)
             judged @!judge-results
             reran @!rerun-results
             experiments @!experiment-results]
         {:n (count refs)
          :needs-review (count (filter :needs-review (vals refs)))
          :recall0 (count (filter :recall20-zero? (vals refs)))
          :judged (count judged)
          :items
          (vec (for [[id r] (sort-by key refs)]
                 (let [qrows (get by-q id)
                       q (get qmap id)]
                   {:id id
                    :query (:query q)
                    :reference (:reference-answer r)
                    :recall0? (boolean (:recall20-zero? r))
                    :needs-review? (boolean (:needs-review r))
                    :note (:note r)
                    :golden-chunk-ids (vec (:golden-chunk-ids q))
                    :goldens (vec (map fetch-golden (:golden-chunk-ids q)))
                    :r20 (if (seq qrows)
                           (fmt (apply max (map #(or (->num (:recall-at-20 %)) 0.0) qrows)))
                           "—")
                    :ans? (boolean (some #(= "true" (:answer-substring-hit? %)) qrows))
                    :responses (vec (distinct (keep :response qrows)))
                    :judge (get judged id)
                    :rerun (get reran id)
                    :experiment (get experiments id)})))}))))

#?(:clj
   (defn save-reference!
     "Persist an edited reference answer back to references.edn (preserving the
      leading comment header + sorted-by-id order). `reviewed?` clears the
      :needs-review flag. Invalidates any stale judge verdict for the id."
     [id new-reference reviewed?]
     (let [raw (slurp references-path)
           m (edn/read-string raw)
           cur (get m id {})
           updated (cond-> (assoc cur :reference-answer new-reference)
                     reviewed? (dissoc :needs-review))
           m2 (into (sorted-map) (assoc m id updated))
           header (subs raw 0 (str/index-of raw "\n{"))
           out (str header "\n" (with-out-str (pp/pprint m2)))]
       (spit references-path out)
       (swap! !judge-results dissoc id)           ; reference changed → old verdict is stale
       {:ok true})))

#?(:clj
   (defn run-judge!
     "Judge id's latest agent answer (re-run overlay preferred) against its
      reference. Stores the verdict in the judge overlay. Returns the verdict."
     [id]
     (let [refs (read-edn-file references-path)
           qmap (into {} (for [row (:questions (read-edn-file questions-path))] [(:id row) row]))
           reference (get-in refs [id :reference-answer])
           query (get-in qmap [id :query])
           response (or (get-in @!rerun-results [id :response])
                        (let [f (latest-csv)
                              rows (when (and f (.exists f)) (:rows (csv-snapshot f)))]
                          (first (distinct (keep :response (get (group-by :question-id rows) id))))))
           v (judge/judge-answer (:tenant review-scope)
                                 {:query query :reference reference :response response})]
       (swap! !judge-results assoc id (assoc v :at (System/currentTimeMillis)))
       v)))

#?(:clj
   (defn rerun-question!
     "Re-run the full agent loop for one question (reusing the latest sweep's
      config + scope), refreshing its agent answer + recall in the re-run overlay.
      Uses the src-dev sweep runner via requiring-resolve (dev/admin tool).
      Heavy — invokes the agent LLM."
     [id]
     (try
       (let [run-matrix (requiring-resolve 'digdir.skills.enrichment.eval-runner/run-comparison)
             load-questions! load-question-rows
             q (first (filter #(= id (:id %)) (load-questions!)))
             f (latest-csv)
             matrix (read-edn-file (str (.getParentFile ^java.io.File f) "/matrix.edn"))
             scope (:execution-scope matrix)
             config (first (:configs matrix))
             tmp (str (System/getProperty "java.io.tmpdir") "/rerun-" id "-" (System/currentTimeMillis))
             ;; :out-dir dropped — run-comparison persists nothing. The sweep
             ;; runner's CSV/provenance machinery is not what a single re-run
             ;; needs, and it is the part that lived in src-dev.
             {:keys [rows]} (run-matrix {:configs [config] :questions [q] :repeats 1
                                         :execution-scope scope})
             row (first rows)]
         (swap! !rerun-results assoc id {:response (:response row)
                                         :recall-at-20 (:recall-at-20 row)
                                         :answer-substring-hit? (:answer-substring-hit? row)
                                         :status (:status row)
                                         :at (System/currentTimeMillis)})
         (swap! !judge-results dissoc id)         ; answer changed → old verdict is stale
         {:ok true})
       (catch Exception e {:ok false :error (str (.getMessage e))}))))

#?(:clj
   (defn run-enrichment-experiment!
     "The self-improvement proof for one question: (1) generate hypothetical-
      question enrichment for each golden chunk and write it to the enrichment
      collection, then (2) run the agent enrichment-OFF vs enrichment-ON (N=3,
      corpus-aware-2hop on both) and judge each run. Stores a before/after
      summary (recall@20 + judge score/verdicts) in the experiment overlay.

      Side effect: writes enrichments to the corpus (idempotent on chunk-id;
      reversible via enrichment-revert-chunk). Heavy (~minutes): propose+apply
      per golden + 2×N agent runs + judging. src-dev runner/skills via
      requiring-resolve (dev/admin tool)."
     [id]
     (try
       (let [tenant (:tenant review-scope)
             qmap (into {} (for [row (:questions (read-edn-file questions-path))] [(:id row) row]))
             golden-ids (vec (:golden-chunk-ids (get qmap id)))
             {:keys [chunks]} (collections)
             enr-coll (enr-naming/enrichment-collection-name-from-base chunks :hypothetical-questions)
             propose (requiring-resolve 'digdir.skills.enrichment.propose-questions/execute-propose-questions)
             apply-fn (requiring-resolve 'digdir.skills.enrichment.apply-questions/execute-apply-questions)
             run-matrix (requiring-resolve 'digdir.skills.enrichment.eval-runner/run-comparison)
             load-questions! load-question-rows
             compute-verdict (requiring-resolve 'digdir.skills.enrichment.batch-verdict/compute-batch-verdict)
             revert (requiring-resolve 'digdir.skills.enrichment.revert-chunk/execute-revert-chunk)
             ;; 1. generate + apply enrichment for each golden chunk (remember
             ;;    each prompt-hash so we can narrow a revert to this exact pass)
             sample (atom nil)
             hashes (atom {})
             _ (doseq [cid golden-ids]
                 (let [g (fetch-golden cid)
                       prop (:outputs (propose {:inputs {:chunk-id cid :chunk-content (:content g)
                                                         :doc-title (:title g) :doc-url (:url g)}
                                                :parameters {:question-count 4}
                                                :skill-params {:tenant tenant}}))]
                   (swap! hashes assoc cid (get-in prop [:provenance :prompt-hash]))
                   (when-not @sample (reset! sample (second (:questions prop))))
                   (apply-fn {:inputs {:proposal prop :collection-name enr-coll :doc-num (:doc-num g)}
                              :skill-params {:tenant tenant}})))
             ;; 2. A/B: enrichment-off vs -on, judged, N=3
             full-q (first (filter #(= id (:id %)) (load-questions!)))
             base-sp {:builtin/query-planner {:enabled true :expansion-mode :corpus-aware-2hop}}
             configs [{:id "enrichment-off" :skill-graph-id :builtin/agent-rag-graph-bundled :skill-params base-sp}
                      {:id "enrichment-on" :skill-graph-id :builtin/agent-rag-graph-bundled
                       :skill-params (assoc base-sp :builtin/retrieval {:enrichment-types [:hypothetical-questions]})}]
             out (str (System/getProperty "java.io.tmpdir") "/enrich-exp-" id "-" (System/currentTimeMillis))
             ;; :judge? and :out-dir dropped: run-comparison neither judges nor
             ;; persists. The gate below reads only top-20 membership, which
             ;; never depended on the judge.
             {:keys [rows]} (run-matrix {:configs configs :questions [full-q] :repeats 3
                                         :execution-scope (assoc review-scope :agent-id "builtin/agent-rag-agent")})
             ;; 3. GATE: same top-20 keep criterion the self-improve loop uses —
             ;;    keep a chunk only if enrichment-on raises its top-20 presence.
             {:keys [kept reverted verdicts]} (compute-verdict rows id golden-ids [])
             ;; 4. auto-revert the chunks that did NOT improve (corpus stays clean)
             _ (doseq [cid reverted]
                 (revert {:inputs {:chunk-id cid :collection-name enr-coll :prompt-hash (get @hashes cid)}
                          :parameters {:dry-run? false}
                          :skill-params {:tenant tenant}}))
             by-cfg (group-by :config-id rows)
             mean (fn [k rs] (let [vs (keep k rs)]
                               (when (seq vs) (/ (reduce + (map double vs)) (count vs)))))
             agg (fn [cid] (let [rs (get by-cfg cid)]
                             {:recall20 (some-> (mean :recall-at-20 rs) (->> (format "%.2f")))
                              :judge-score (some-> (mean :answer-judge-score rs) (->> (format "%.2f")))
                              :verdicts (frequencies (keep :answer-judge-verdict rs))
                              :n (count rs)}))]
         (swap! !experiment-results assoc id
                {:enrichment-collection enr-coll
                 :n-goldens (count golden-ids)
                 :sample-question @sample
                 :off (agg "enrichment-off")
                 :on (agg "enrichment-on")
                 :kept (vec kept)
                 :reverted (vec reverted)
                 :decision (if (seq kept)
                             (str "KEPT " (count kept) "/" (count golden-ids) " (improved top-20)")
                             "REVERTED — no top-20 improvement, corpus restored")
                 :chunk-verdicts (into {} (for [[c v] verdicts]
                                            [c {:top20-off (format "%.2f" (:top20-off v))
                                                :top20-on (format "%.2f" (:top20-on v))
                                                :keep? (:keep? v)}]))
                 :at (System/currentTimeMillis)})
         (swap! !judge-results dissoc id)
         {:ok true})
       (catch Exception e {:ok false :error (str (.getMessage e))}))))

#?(:clj
   (defn- fmt-dur [ms]
     (let [s (long (/ (max 0 (long ms)) 1000)) m (quot s 60) sec (rem s 60)]
       (if (pos? m) (str m "m " sec "s") (str sec "s")))))

#?(:clj
   (defn read-progress
     "Live progress for the newest sweep, read fresh each tick from
      `<dir>/progress.edn` (written by the runner at start / each run / done).
      Returns nil for sweeps predating the progress file (no banner shown).
      ETA is wall-clock: elapsed × remaining / completed."
     [_tick]
     (when-let [f (latest-csv)]
       (let [pf (io/file (.getParentFile f) "progress.edn")]
         (when (.exists pf)
           (when-let [p (try (edn/read-string (slurp pf)) (catch Exception _ nil))]
             (let [{:keys [total completed status started-at-ms finished-at-ms current]} p
                   now (System/currentTimeMillis)
                   end (or (when (= status "done") finished-at-ms) now)
                   elapsed (- end (or started-at-ms now))
                   remaining (max 0 (- (long (or total 0)) (long (or completed 0))))
                   eta (when (and (= status "running") (pos? (long (or completed 0))))
                         (long (* (/ (double elapsed) (long completed)) remaining)))]
               {:status status
                :total total
                :completed completed
                :pct (if (pos? (long (or total 0)))
                       (Math/round (* 100.0 (/ (long completed) (double total)))) 0)
                :elapsed (fmt-dur elapsed)
                :eta (when eta (fmt-dur eta))
                :current current})))))))

;; =============================================================================
;; Client: the live dashboard
;; =============================================================================

(e/defn Stat [label value]
  (dom/div
   (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.15rem"
                       :padding "0.5rem 0.9rem" :background "#f8fafc"
                       :border "1px solid #e2e8f0" :border-radius "8px" :min-width "84px"}})
   (dom/span (dom/props {:style {:font-size "0.7rem" :color "#64748b" :text-transform "uppercase"}})
             (dom/text label))
   (dom/span (dom/props {:style {:font-size "1.25rem" :font-weight "700" :color "#0f172a"}})
             (dom/text (str value)))))

(e/defn CountStat [label n]
  (let [bad? (pos? n)]
    (dom/div
     (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.15rem"
                         :padding "0.5rem 0.9rem" :border-radius "8px" :min-width "84px"
                         :background (if bad? "#fee2e2" "#f8fafc")
                         :border (str "1px solid " (if bad? "#fca5a5" "#e2e8f0"))}})
     (dom/span (dom/props {:style {:font-size "0.7rem" :text-transform "uppercase"
                                   :color (if bad? "#991b1b" "#64748b")}})
               (dom/text label))
     (dom/span (dom/props {:style {:font-size "1.25rem" :font-weight "700"
                                   :color (if bad? "#991b1b" "#0f172a")}})
               (dom/text (str n))))))

(e/defn RateStat [label rate]
  ;; like CountStat but for a percentage string ("0%"…): highlights red when
  ;; the rate is anything but zero. Used for length-cut / empty detectors.
  (let [bad? (not (or (= rate "0%") (nil? rate)))]
    (dom/div
     (dom/props {:style {:display "flex" :flex-direction "column" :gap "0.15rem"
                         :padding "0.5rem 0.9rem" :border-radius "8px" :min-width "84px"
                         :background (if bad? "#fee2e2" "#f8fafc")
                         :border (str "1px solid " (if bad? "#fca5a5" "#e2e8f0"))}})
     (dom/span (dom/props {:style {:font-size "0.7rem" :text-transform "uppercase"
                                   :color (if bad? "#991b1b" "#64748b")}})
               (dom/text label))
     (dom/span (dom/props {:style {:font-size "1.25rem" :font-weight "700"
                                   :color (if bad? "#991b1b" "#0f172a")}})
               (dom/text (str rate))))))

(e/defn Td [v]
  (dom/td (dom/props {:style {:padding "0.3rem 0.5rem" :white-space "nowrap"}})
          (dom/text (str v))))

(e/defn StatusTd [status]
  (let [ok? (= "complete" status)]
    (dom/td (dom/props {:style {:padding "0.3rem 0.5rem" :white-space "nowrap"
                                :font-weight (if ok? "400" "700")
                                :color (if ok? "#334155" "#991b1b")}})
            (dom/text (str status)))))

(e/defn BoolTd [v]
  (dom/td (dom/props {:style {:padding "0.3rem 0.5rem" :font-weight "600"
                              :color (if (= "true" v) "#16a34a" "#cbd5e1")}})
          (dom/text (if (= "true" v) "✓" "·"))))

(e/defn Th [label]
  (dom/th (dom/props {:style {:padding "0.3rem 0.5rem" :font-size "0.7rem"
                              :color "#64748b" :text-transform "uppercase"}})
          (dom/text label)))

(e/defn ProgressBanner [p]
  (let [running? (= "running" (:status p))
        pct      (:pct p)
        cur      (:current p)
        bg (if running? "#eff6ff" "#f0fdf4")
        bd (if running? "#bfdbfe" "#bbf7d0")
        fg (if running? "#1d4ed8" "#15803d")
        bar (if running? "#3b82f6" "#22c55e")]
    (dom/div
     (dom/props {:style {:padding "0.7rem 0.9rem" :margin-bottom "1.1rem"
                         :background bg :border (str "1px solid " bd) :border-radius "8px"}})
     (dom/div
      (dom/props {:style {:display "flex" :justify-content "space-between" :gap "1rem"
                          :font-size "0.85rem" :font-weight "600" :color fg :margin-bottom "0.45rem"}})
      (dom/span (dom/text (str (if running? "▶ Running " "✓ Completed ")
                               (:completed p) "/" (:total p) " (" pct "%)")))
      (dom/span (dom/text (if running?
                            (str "elapsed " (:elapsed p)
                                 (when (:eta p) (str " · ETA ~" (:eta p)))
                                 (when cur (str " · now: " (:question-id cur) " #" (:repeat cur))))
                            (str "in " (:elapsed p))))))
     (dom/div
      (dom/props {:style {:height "6px" :background "#e2e8f0" :border-radius "3px" :overflow "hidden"}})
      (dom/div (dom/props {:style {:height "100%" :width (str pct "%") :background bar
                                   :transition "width 0.3s ease"}}))))))

(e/defn Badge [label bg fg]
  (dom/span
   (dom/props {:style {:display "inline-block" :padding "0.1rem 0.5rem" :border-radius "999px"
                       :font-size "0.68rem" :font-weight "700" :background bg :color fg}})
   (dom/text label)))

(e/defn ModeButton [!mode target label active?]
  (dom/button
   (dom/props {:style {:padding "0.35rem 0.9rem" :border "1px solid #cbd5e1" :border-radius "7px"
                       :cursor "pointer" :font-size "0.82rem" :font-weight "600"
                       :background (if active? "#0f172a" "white")
                       :color (if active? "white" "#334155")}})
   (dom/text label)
   (let [[t _] (e/Token (dom/On "click" identity nil))]
     (when t (reset! !mode target) (t)))))

(e/defn VerdictBadge [judge]
  (let [v (:verdict judge)
        [bg fg] (case v
                  "correct" ["#dcfce7" "#166534"]
                  "partial" ["#fef9c3" "#854d0e"]
                  "incorrect" ["#fee2e2" "#991b1b"]
                  ["#e2e8f0" "#475569"])]
    (dom/span
     (dom/props {:style {:display "inline-block" :padding "0.1rem 0.5rem" :border-radius "999px"
                         :font-size "0.7rem" :font-weight "700" :background bg :color fg}})
     (dom/text (str "judge: " v (when (:score judge) (str " (" (:score judge) ")")))))))

(e/defn DifficultyBadge [d]
  (let [[label bg fg] (case (long d)
                        1 ["trivial" "#dcfce7" "#166534"]
                        2 ["easy" "#ecfccb" "#3f6212"]
                        3 ["moderate" "#fef9c3" "#854d0e"]
                        4 ["hard" "#ffedd5" "#9a3412"]
                        5 ["very hard" "#fee2e2" "#991b1b"]
                        ["?" "#e2e8f0" "#475569"])]
    (dom/span
     (dom/props {:style {:display "inline-block" :padding "0.1rem 0.5rem" :border-radius "999px"
                         :font-size "0.7rem" :font-weight "700" :background bg :color fg}})
     (dom/text (str "difficulty " d "/5 · " label)))))

(def code-pre-style
  {:white-space "pre-wrap" :font-size "0.78rem" :line-height "1.45" :background "#0f172a"
   :color "#e2e8f0" :padding "0.6rem 0.7rem" :border-radius "6px" :margin "0.4rem 0 0" :overflow-x "auto"})

(def summary-style {:cursor "pointer" :font-size "0.78rem" :color "#0369a1"})

(e/defn EditableReference [id reference]
  (e/client
   (let [!edit (atom reference)
         text (e/watch !edit)
         !reviewed (atom false)
         reviewed (e/watch !reviewed)]
     (dom/div
      (dom/textarea
       (dom/props {:style {:width "100%" :min-height "5.5rem" :font-size "0.82rem" :line-height "1.5"
                           :padding "0.5rem 0.7rem" :border "1px solid #cbd5e1" :border-radius "6px"
                           :box-sizing "border-box" :font-family "inherit" :resize "vertical"
                           :background "#f8fafc"}
                   :value text})
       (dom/On "input" #(reset! !edit (.. % -target -value)) nil))
      (dom/div
       (dom/props {:style {:display "flex" :align-items "center" :gap "0.7rem" :margin-top "0.4rem"}})
       (dom/label
        (dom/props {:style {:font-size "0.75rem" :color "#475569" :display "flex"
                            :align-items "center" :gap "0.3rem" :cursor "pointer"}})
        (dom/input (dom/props {:type "checkbox" :checked reviewed})
                   (dom/On "change" #(reset! !reviewed (.. % -target -checked)) nil))
        (dom/text "mark reviewed"))
       (dom/button
        (dom/props {:style {:padding "0.3rem 0.8rem" :border "none" :border-radius "6px"
                            :background "#0f172a" :color "white" :font-size "0.78rem"
                            :font-weight "600" :cursor "pointer"}})
        (dom/text "Save reference")
        (when-some [token (let [ev (dom/On "click" identity nil)
                                [t _err] (e/Token ev)]
                            (dom/props {:aria-busy (some? t) :disabled (some? t)})
                            t)]
          (let [result (e/server (let [qid (e/client id) txt (e/client text) rv (e/client reviewed)]
                                   (e/Offload #(do (save-reference! qid txt rv) ::ok))))]
            (case result ::ok (token) (token))))))))))

(e/defn JudgeControls [id judge]
  (dom/div
   (dom/props {:style {:display "flex" :align-items "center" :gap "0.6rem" :flex-wrap "wrap"
                       :margin-top "0.55rem"}})
   (dom/button
    (dom/props {:style {:padding "0.3rem 0.8rem" :border "1px solid #16a34a" :border-radius "6px"
                        :background "white" :color "#166534" :font-size "0.78rem"
                        :font-weight "600" :cursor "pointer"}})
    (dom/text "Re-eval (judge)")
    (when-some [token (let [ev (dom/On "click" identity nil)
                            [t _err] (e/Token ev)]
                        (dom/props {:aria-busy (some? t) :disabled (some? t)})
                        t)]
      (let [result (e/server (let [qid (e/client id)]
                               (e/Offload #(do (run-judge! qid) ::ok))))]
        (case result ::ok (token) (token)))))
   (when judge
     (VerdictBadge judge)
     (when (:difficulty judge) (DifficultyBadge (:difficulty judge)))
     (dom/span (dom/props {:style {:font-size "0.72rem" :color "#64748b"}})
               (dom/text (str (:rationale judge) " · " (:judge-model judge)))))))

(e/defn RerunControls [id rerun]
  (dom/div
   (dom/props {:style {:margin-top "0.45rem"}})
   (dom/button
    (dom/props {:style {:padding "0.3rem 0.8rem" :border "1px solid #cbd5e1" :border-radius "6px"
                        :background "white" :color "#334155" :font-size "0.78rem"
                        :font-weight "600" :cursor "pointer"}})
    (dom/text "Re-run sweep")
    (when-some [token (let [ev (dom/On "click" identity nil)
                            [t _err] (e/Token ev)]
                        (dom/props {:aria-busy (some? t) :disabled (some? t)})
                        t)]
      (let [result (e/server (let [qid (e/client id)]
                               (e/Offload #(do (rerun-question! qid) ::ok))))]
        (case result ::ok (token) (token)))))
   (when rerun
     (dom/details
      (dom/props {:style {:margin-top "0.4rem"}})
      (dom/summary (dom/props {:style summary-style})
                   (dom/text (str "Re-run result · recall@20 " (:recall-at-20 rerun)
                                  " · substring " (if (= "true" (:answer-substring-hit? rerun)) "✓" "·"))))
      (dom/pre (dom/props {:style code-pre-style}) (dom/text (str (:response rerun))))))))

(e/defn BeforeAfter [label off on]
  (dom/div
   (dom/props {:style {:display "flex" :gap "0.45rem" :align-items "baseline" :font-size "0.8rem"
                       :margin-top "0.15rem"}})
   (dom/span (dom/props {:style {:color "#6b21a8" :min-width "92px"}}) (dom/text label))
   (dom/span (dom/props {:style {:color "#991b1b"}}) (dom/text (str "off " (or off "—"))))
   (dom/span (dom/props {:style {:color "#94a3b8"}}) (dom/text "→"))
   (dom/span (dom/props {:style {:color "#166534" :font-weight "700"}}) (dom/text (str "on " (or on "—"))))))

(e/defn ExperimentControls [id experiment]
  (dom/div
   (dom/props {:style {:margin-top "0.55rem" :padding-top "0.5rem" :border-top "1px dashed #e2e8f0"}})
   (dom/button
    (dom/props {:style {:padding "0.32rem 0.85rem" :border "none" :border-radius "6px"
                        :background "#7c3aed" :color "white" :font-size "0.78rem"
                        :font-weight "700" :cursor "pointer"}})
    (dom/text "✨ Enrich & prove (~3 min)")
    (when-some [token (let [ev (dom/On "click" identity nil)
                            [t _err] (e/Token ev)]
                        (dom/props {:aria-busy (some? t) :disabled (some? t)})
                        t)]
      (let [result (e/server (let [qid (e/client id)]
                               (e/Offload #(do (run-enrichment-experiment! qid) ::ok))))]
        (case result ::ok (token) (token)))))
   (when experiment
     (let [kept? (seq (:kept experiment))]
       (dom/div
        (dom/props {:style {:margin-top "0.5rem" :background "#faf5ff" :border "1px solid #e9d5ff"
                            :border-radius "8px" :padding "0.6rem 0.8rem"}})
        (dom/div (dom/props {:style {:font-weight "700" :color "#6b21a8" :font-size "0.8rem"
                                     :margin-bottom "0.3rem"}})
                 (dom/text (str "Enrichment experiment · " (:n-goldens experiment)
                                " golden(s) enriched · N=" (:n (:off experiment)))))
        ;; gate decision banner
        (dom/div (dom/props {:style {:display "inline-block" :padding "0.15rem 0.6rem" :border-radius "6px"
                                     :font-size "0.76rem" :font-weight "700" :margin-bottom "0.4rem"
                                     :background (if kept? "#dcfce7" "#fee2e2")
                                     :color (if kept? "#166534" "#991b1b")}})
                 (dom/text (:decision experiment)))
        (BeforeAfter "recall@20" (get-in experiment [:off :recall20]) (get-in experiment [:on :recall20]))
        (BeforeAfter "judge score" (get-in experiment [:off :judge-score]) (get-in experiment [:on :judge-score]))
        ;; per-chunk top-20 presence (the gate signal)
        (e/for [pair (e/diff-by first (vec (:chunk-verdicts experiment)))]
          (BeforeAfter (str "top-20 " (subs (str (first pair)) 0 (min 8 (count (str (first pair))))))
                       (:top20-off (second pair)) (:top20-on (second pair))))
        (dom/div (dom/props {:style {:font-size "0.72rem" :color "#7c3aed" :margin-top "0.35rem"}})
                 (dom/text (str "judge verdicts: off " (pr-str (get-in experiment [:off :verdicts]))
                                " → on " (pr-str (get-in experiment [:on :verdicts])))))
        (when (:sample-question experiment)
          (dom/div (dom/props {:style {:font-size "0.72rem" :color "#64748b" :font-style "italic"
                                       :margin-top "0.3rem"}})
                   (dom/text (str "bridge question generated: " (:sample-question experiment))))))))))

(e/defn GoldenChunks [goldens]
  (when (seq goldens)
    (dom/details
     (dom/props {:style {:margin-top "0.5rem"}})
     (dom/summary (dom/props {:style summary-style})
                  (dom/text (str "Golden chunk" (when (> (count goldens) 1) "s") " (" (count goldens) ")")))
     (e/for [g (e/diff-by :chunk-id goldens)]
       (dom/div
        (dom/props {:style {:margin-top "0.45rem"}})
        (when (:title g)
          (dom/div (dom/props {:style {:font-weight "600" :font-size "0.76rem" :color "#0f172a"}})
                   (dom/text (:title g))))
        (dom/div (dom/props {:style {:font-size "0.68rem" :color "#94a3b8" :font-family "ui-monospace, monospace"}})
                 (dom/text (:chunk-id g)))
        (dom/pre (dom/props {:style (assoc code-pre-style :background "#1e293b")})
                 (dom/text (or (:content g) "(chunk not found in collection)"))))))))

(e/defn ReferenceCard [item]
  (dom/div
   (dom/props {:style {:border "1px solid #e2e8f0" :border-radius "10px"
                       :padding "0.9rem 1rem" :margin-bottom "0.8rem" :background "white"}})
   (dom/div
    (dom/props {:style {:display "flex" :align-items "center" :gap "0.5rem"
                        :flex-wrap "wrap" :margin-bottom "0.5rem"}})
    (dom/strong (dom/props {:style {:font-size "0.85rem" :font-family "ui-monospace, monospace"}})
                (dom/text (:id item)))
    (when (:needs-review? item) (Badge "needs review" "#fef3c7" "#92400e"))
    (when (:recall0? item) (Badge "golden not retrieved" "#fee2e2" "#991b1b"))
    (dom/span (dom/props {:style {:margin-left "auto" :font-size "0.72rem" :color "#64748b"}})
              (dom/text (str "recall@20 " (:r20 item) " · substring " (if (:ans? item) "✓" "·")))))
   (when (:query item)
     (dom/div (dom/props {:style {:font-size "0.85rem" :color "#475569" :font-style "italic"
                                  :margin-bottom "0.5rem"}})
              (dom/text (:query item))))
   ;; editable reference answer
   (EditableReference (:id item) (:reference item))
   (when (:note item)
     (dom/div (dom/props {:style {:font-size "0.78rem" :color "#92400e" :background "#fffbeb"
                                  :padding "0.4rem 0.6rem" :border-radius "6px" :margin-top "0.45rem"}})
              (dom/text (str "⚠ " (:note item)))))
   ;; judge re-eval + verdict
   (JudgeControls (:id item) (:judge item))
   ;; agent answer(s) from the latest sweep
   (let [resps (:responses item)]
     (when (seq resps)
       (dom/details
        (dom/props {:style {:margin-top "0.5rem"}})
        (dom/summary (dom/props {:style summary-style})
                     (dom/text (str "Agent answer" (when (> (count resps) 1) "s")
                                    " from latest sweep (" (count resps) ")")))
        (e/for [resp (e/diff-by identity resps)]
          (dom/pre (dom/props {:style code-pre-style}) (dom/text resp))))))
   ;; golden chunk(s)
   (GoldenChunks (:goldens item))
   ;; re-run sweep + fresh answer
   (RerunControls (:id item) (:rerun item))
   ;; enrich & prove: generate enrichment for the goldens, then off/on A/B
   (ExperimentControls (:id item) (:experiment item))))

(e/defn ReferenceReview [tick]
  (let [summary (e/server (when-let [r (read-review tick)] (dissoc r :items)))]
    (if (nil? summary)
      (dom/div (dom/props {:style {:color "#64748b" :padding "1rem"}})
               (dom/text "No references.edn found under test/fixtures/sweep/."))
      (dom/div
       (dom/div (dom/props {:style {:font-size "0.85rem" :color "#64748b" :margin-bottom "1rem"}})
                (dom/text (str (:n summary) " references · " (:needs-review summary)
                               " need review · " (:recall0 summary) " golden-not-retrieved")))
       (e/for [item (e/server (e/diff-by :id (:items (read-review tick))))]
         (ReferenceCard item))))))

(e/defn ManifestPanel [tick]
  ;; Part A model manifest for the latest sweep: which agent model + quant +
  ;; engine + mode ran, the key sampling knobs, and the judge / generation
  ;; models. Renders nothing for sweeps predating models.edn.
  (let [m (e/server (read-model-manifest tick))]
    (when m
      (let [a (:agent m)]
        (dom/div
         (dom/props {:style {:margin-bottom "1.1rem" :background "#f8fafc" :border "1px solid #e2e8f0"
                             :border-radius "8px" :padding "0.6rem 0.9rem" :font-size "0.8rem"}})
         (dom/div
          (dom/props {:style {:display "flex" :gap "0.5rem" :align-items "center"
                              :flex-wrap "wrap" :margin-bottom "0.4rem"}})
          (dom/strong (dom/props {:style {:color "#0f172a"}}) (dom/text "Agent"))
          (dom/span (dom/props {:style {:font-family "ui-monospace, monospace" :color "#334155"}})
                    (dom/text (str (:model a))))
          (when (:quantization a) (Badge (:quantization a) "#e0e7ff" "#3730a3"))
          (when (:engine a) (Badge (:engine a) "#dbeafe" "#1e40af"))
          (when (:mode a) (Badge (str "mode " (:mode a)) "#fef9c3" "#854d0e"))
          (when (:reasoning-effort a) (Badge (str "reasoning " (:reasoning-effort a)) "#ffedd5" "#9a3412"))
          (when (:arch a) (dom/span (dom/props {:style {:font-size "0.72rem" :color "#64748b"}})
                                    (dom/text (str (:arch a)))))
          (when (:context a) (dom/span (dom/props {:style {:font-size "0.72rem" :color "#64748b"}})
                                       (dom/text (str "ctx " (:context a)))))
          (when (:state a) (dom/span (dom/props {:style {:font-size "0.72rem" :color "#64748b"}})
                                     (dom/text (str "· " (:state a))))))
         ;; judge + generation
         (dom/div (dom/props {:style {:font-size "0.74rem" :color "#475569" :margin-bottom "0.35rem"}})
                  (dom/text (str "judge " (get-in m [:judge :model])
                                 " (" (get-in m [:judge :provider]) ")"
                                 " · generation " (get-in m [:generation :model])
                                 " (" (get-in m [:generation :mode]) ")")))
         ;; sampling snapshot
         (when (seq (:env m))
           (dom/div (dom/props {:style {:font-size "0.7rem" :color "#64748b"
                                        :font-family "ui-monospace, monospace"}})
                    (dom/text (apply str (interpose "  " (map (fn [[k v]] (str k "=" v)) (:env m)))))))
         (when (:captured-at m)
           (dom/div (dom/props {:style {:font-size "0.68rem" :color "#94a3b8" :margin-top "0.25rem"}})
                    (dom/text (str "captured " (:captured-at m))))))))))

(e/defn ConfigPanel [tick]
  (let [agent-id (e/server (:agent-id (read-config-info tick)))]
    (when agent-id
      (dom/details
       (dom/props {:style {:margin-bottom "1.1rem" :background "#f8fafc" :border "1px solid #e2e8f0"
                           :border-radius "8px" :padding "0.5rem 0.8rem"}})
       (dom/summary (dom/props {:style {:cursor "pointer" :font-size "0.82rem" :font-weight "600"
                                        :color "#334155"}})
                    (dom/text (str "Config under test · agent " agent-id " · effective skill-params")))
       (e/for [c (e/server (e/diff-by :id (:configs (read-config-info tick))))]
         (dom/div
          (dom/props {:style {:margin-top "0.5rem"}})
          (dom/div (dom/props {:style {:font-weight "700" :font-size "0.78rem" :color "#0f172a"}})
                   (dom/text (str (:id c) " · " (:skill-graph-id c))))
          (dom/pre (dom/props {:style (assoc code-pre-style :background "#1e293b")})
                   (dom/text (:effective c)))))))))

(e/defn Sweep-dashboard []
  (e/client
   (let [!mode (atom :results)
         mode (e/watch !mode)
         tick (quot (e/System-time-secs) 2)         ; live: re-evaluate every 2s
         dir  (e/server (:dir (read-sweep tick)))
         n    (e/server (:n (read-sweep tick)))
         s    (e/server (:summary (read-sweep tick)))
         prog (e/server (read-progress tick))]
     (dom/div
      (dom/props {:style {:padding "1.5rem" :font-family "system-ui, -apple-system, sans-serif"}})
      (dom/h2 (dom/props {:style {:margin "0 0 0.2rem"}}) (dom/text "Sweep dashboard"))
      (dom/div (dom/props {:style {:color "#64748b" :font-size "0.85rem" :margin-bottom "1.1rem"}})
               (dom/text (str dir " · " n " rows · live (refreshes every 2s)")))
      (when prog (ProgressBanner prog))
      (dom/div
       (dom/props {:style {:display "flex" :gap "0.5rem" :margin-bottom "1.25rem"}})
       (ModeButton !mode :results "Results" (= mode :results))
       (ModeButton !mode :references "Reference review" (= mode :references)))
      (case mode
        :references (ReferenceReview tick)
        ;; default :results
        (dom/div
         (ManifestPanel tick)
         (ConfigPanel tick)
         (dom/div
          (dom/props {:style {:display "flex" :gap "0.6rem" :flex-wrap "wrap" :margin-bottom "1.25rem"}})
          (Stat "Rows" (str n))
          (Stat "Answer" (str (:answer-rate s)))
          (Stat "In pool" (str (:pool-rate s)))
          (Stat "Top-20" (str (:display-rate s)))
          (Stat "Read" (str (:read-rate s)))
          (Stat "Recall@10" (str (:recall10 s)))
          (Stat "Recall@20" (str (:recall20 s)))
          ;; Local-model signals (Part A): mean hidden-thinking spend +
          ;; token-cap / silent-cut-off detectors.
          (Stat "Reasoning ⌀" (str (or (:reasoning-tokens s) 0)))
          (RateStat "Length-cut" (or (:length-finish-rate s) "0%"))
          (RateStat "Empty" (or (:empty-rate s) "0%"))
          (CountStat "Errors" (or (:errors s) 0))
          (CountStat "No answer" (or (:no-answer s) 0))
          (CountStat "Judge err" (or (:judge-errors s) 0)))
         ;; finish-reason histogram for the latest sweep (terminal agent turn)
         (when (seq (:finish-hist s))
           (dom/div (dom/props {:style {:font-size "0.75rem" :color "#64748b" :margin-bottom "1.1rem"}})
                    (dom/text (str "finish-reason: "
                                   (apply str (interpose " · " (map (fn [[k v]] (str k " " v))
                                                                     (:finish-hist s))))))))
         (dom/table
          (dom/props {:style {:border-collapse "collapse" :width "100%" :font-size "0.8rem"}})
          (dom/thead
           (dom/tr (dom/props {:style {:text-align "left" :border-bottom "2px solid #e2e8f0"}})
                   (Th "Question") (Th "Config") (Th "Status") (Th "R@10") (Th "R@20")
                   (Th "Pool") (Th "Top20") (Th "Read") (Th "Ans") (Th "Passes") (Th "Enr")
                   (Th "Reason-tok") (Th "Finish") (Th "Len") (Th "Empty")))
          (dom/tbody
           (e/for [row (e/server (e/diff-by :run-id (:rows (read-sweep tick))))]
             (dom/tr (dom/props {:style {:border-bottom "1px solid #f1f5f9"}})
                     (Td (:question-id row)) (Td (:config-id row)) (StatusTd (:status row))
                     (Td (:recall-at-10 row)) (Td (:recall-at-20 row))
                     (BoolTd (:golden-in-search-pool? row)) (BoolTd (:golden-in-display? row))
                     (BoolTd (:golden-read? row)) (BoolTd (:answer-substring-hit? row))
                     (Td (:search-passes row)) (Td (:enrichment-hits row))
                     (Td (:reasoning-tokens row)) (Td (:finish-reason row))
                     (BoolTd (:length-finish? row)) (BoolTd (:empty? row))))))))))))
