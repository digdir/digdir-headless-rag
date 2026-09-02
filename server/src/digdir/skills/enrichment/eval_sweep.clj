(ns digdir.skills.enrichment.eval-sweep
  "`:builtin/enrichment-eval-sweep` — the batch keep/revert gate for the
   self-improve loop.

   Slice 2b of #82 (#94). This namespace used to live in src-dev because it
   embedded the research harness: it called `digdir.sweep.runner/run-matrix`
   to execute the comparison and `digdir.sweep.questions/load-questions!` to
   find its regression questions. Any graph naming this skill was therefore
   undeployable — which is exactly why the outer self-improve graph had to
   stay in src-dev, a constraint slice 1 recorded and deferred (see
   `digdir.skills.enrichment.questions-graph`).

   Measurement is now separated from the graph rather than embedded in it:

     execution  -> digdir.skills.enrichment.eval-runner/run-comparison  (src)
     scoring    -> digdir.skills.enrichment.batch-verdict               (src)
     questions  -> supplied as DATA by the caller

   WHAT THE HARNESS WAS ACTUALLY BEING ASKED FOR — measured, not assumed:

   - Of everything `run-matrix` returns, this skill destructured `:rows` and
     `:out-dir`, and the verdict then read five keys off each row. Slice 2a
     reimplemented precisely that as `eval-runner/run-comparison`. `:out-dir`
     was CSV persistence, a sweep artifact, and is gone: an accept-or-revert
     decision returns its verdict, it does not leave a spreadsheet behind.

   - Of everything `load-questions!` returns, this skill kept the records
     whose `:id` matched `:regression-question-ids`, and `run-comparison`
     reads only `:id`, `:query`, `:golden-chunk-ids` and the optional
     `:expected-answer-pattern` off each. The other fixture keys were never
     read. Its source is `test/fixtures/sweep/questions.edn`, which no alias
     puts on the production classpath.

   So regression questions arrive as DATA, two ways, both deployable:

     :regression-questions      vector of question maps, used as given
     :regression-question-ids   resolved via `regression-question-source`

   `regression-question-source` is a seam, not a back door. Production leaves
   it empty; a dev build installs the fixture loader into it (see
   `digdir.skills.enrichment.eval-sweep-fixture`). The dependency direction is
   inverted — the harness plugs into the product — so nothing here names a
   `digdir.sweep.*` namespace and the boundary guard stays clean.

   ASKING FOR A GUARD WE CANNOT SUPPLY IS AN ERROR, NOT A DEGRADATION. If
   `:regression-question-ids` names questions nothing can resolve, this
   throws. Running on without the regression guard would return a verdict
   shaped exactly like a guarded one, quietly keeping enrichments a bystander
   query would have vetoed.

   NO SIMULATED USER — the product question `eval-runner` left to this slice.
   `run-matrix` answered the agent's clarifying questions with
   `digdir.sweep.user-simulator` to keep sweep numbers comparable. This path
   does not. Both configs face the same agent and enrichment is still the
   only delta, so the keep/revert comparison holds; but a question whose
   agent asks for clarification scores differently here than in a historical
   sweep, so those two numbers are not comparable.

   Everything below the seam is unchanged: ONE motivating query carrying all
   freshly-enriched chunks as its goldens, an enrichment-off/on config pair
   that toggles `:enrichment-types` (the write stays in place — nothing is
   reverted in order to measure it), and the per-chunk top-20 K2 criterion at
   N=3 repeats, since n=2 produced a false harmful verdict in the dilution
   probe.

   BASELINE FRAMING via `:expansion-mode`, used by BOTH configs:
     - `:corpus-aware-2hop` (DEFAULT) -> MARGINAL value over production.
     - `:blind`                       -> ABSOLUTE value (old-gate framing)."
  (:require
    [digdir.rag.skills.core :as skills]
    [digdir.skills.enrichment.batch-verdict :as batch-verdict]
    [digdir.skills.enrichment.eval-runner :as eval-runner]))

(def compute-batch-verdict
  "Re-export of digdir.skills.enrichment.batch-verdict/compute-batch-verdict,
   which was promoted to src/ in slice 2a of #82 (#94)."
  batch-verdict/compute-batch-verdict)

;; =============================================================================
;; The regression-question seam
;; =============================================================================

(defonce regression-question-source
  ;; (fn [ids] -> seq of question maps), or nil.
  ;;
  ;; Nil in a production build. A dev build installs a fixture-backed loader
  ;; via `digdir.skills.enrichment.eval-sweep-fixture`, so the research
  ;; question set stays available where it exists without this namespace
  ;; naming it. `defonce` so reloading does not silently uninstall it.
  (atom nil))

(defn resolve-regression-questions
  "Regression questions for a run, from parameters.

   Explicit records win. Ids go through `regression-question-source`, and an
   id that resolves to nothing is an error rather than a silent omission —
   `compute-batch-verdict` cannot distinguish a regression question that
   passed from one that never ran."
  [{:keys [regression-questions regression-question-ids]}]
  (cond
    (seq regression-questions)
    (vec regression-questions)

    (empty? regression-question-ids)
    []

    :else
    (let [source @regression-question-source]
      (when-not source
        (throw (ex-info
                 (str "eval-sweep: :regression-question-ids was set, but no "
                      "regression-question-source is installed. This build "
                      "cannot resolve question ids (the sweep fixture is not "
                      "on the production classpath). Pass the question "
                      "records as :regression-questions instead — each needs "
                      ":id, :query and :golden-chunk-ids.")
                 {:regression-question-ids (vec regression-question-ids)})))
      (let [wanted (set regression-question-ids)
            resolved (vec (filter #(contains? wanted (:id %)) (source regression-question-ids)))
            missing (remove (set (map :id resolved)) regression-question-ids)]
        (when (seq missing)
          (throw (ex-info
                   (str "eval-sweep: regression question ids not found: "
                        (vec missing) ". Running without them would produce a "
                        "verdict indistinguishable from a guarded one.")
                   {:missing (vec missing)
                    :regression-question-ids (vec regression-question-ids)})))
        resolved))))

;; =============================================================================
;; Skill metadata
;; =============================================================================

(def eval-sweep-metadata
  {:skill-id :builtin/enrichment-eval-sweep
   :name "Enrichment eval sweep (batch)"
   :description "Score a batch of enrichments by running the full agent enrichment-off vs -on ONCE for the motivating query, and return a PER-CHUNK keep/revert verdict from the top-20 agent-path decomposition plus a regression set."
   :category :orchestration
   ;; Batch input forms (any of): :chunk-outcomes (foreach collect-as vector of
   ;; maps, chunk-id extracted), :chunk-ids (vector of strings), :chunk-id (single).
   ;; :tenant / :dataset-config-key are declared for documentation but marked
   ;; optional: in a graph step they arrive via skill-params (the execute falls
   ;; back to them), not as graph inputs, so requiring them would reject the step.
   :inputs [:user-query :tenant :dataset-config-key]
   :optional-inputs [:chunk-id :chunk-ids :chunk-outcomes :tenant :dataset-config-key]
   :outputs [:verdicts :batch-summary :rows :summary]
   ;; :max-clarification-rounds is deliberately absent: run-comparison does not
   ;; simulate a user, so declaring the knob would advertise a control that does
   ;; nothing.
   :parameters {:enrichment-type :keyword
                :expansion-mode :keyword
                :repeats :number
                :regression-question-ids :vector
                :regression-questions :vector
                :agent-id :string}
   :version "3.0.0"
   :tags #{:diagnostics :enrichment :self-improve :sweep}})

;; =============================================================================
;; Execution
;; =============================================================================

(def ^:private target-id "enrich-eval-target")

(defn execute-eval-sweep
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [user-query chunk-id chunk-ids chunk-outcomes]} inputs
        ;; In a graph step tenant/dataset arrive via skill-params, not inputs
        ;; (the playground doesn't thread them as graph inputs); standalone
        ;; callers pass them in :inputs. Honor either.
        tenant (or (:tenant inputs) (:tenant skill-params))
        dataset-config-key (or (:dataset-config-key inputs)
                               (:dataset-config-key skill-params)
                               "public-docs")
        ;; Accept the foreach :chunk-outcomes (vector of per-chunk output maps) and
        ;; pull the chunk-id out of each, plus explicit :chunk-ids / :chunk-id.
        chunk-ids (vec (distinct (concat
                                   (keep #(or (:chunk-id %) (when (string? %) %))
                                         (when (sequential? chunk-outcomes) chunk-outcomes))
                                   (when (sequential? chunk-ids) chunk-ids)
                                   (when chunk-id [chunk-id]))))
        ;; Default N=3: n=2 produced a FALSE harmful verdict in the dilution probe.
        repeats (long (or (:repeats parameters) 3))
        enrichment-type (or (:enrichment-type parameters) :hypothetical-questions)
        expansion-mode (or (:expansion-mode parameters) :corpus-aware-2hop)
        agent-id (or (:agent-id parameters) "builtin/agent-rag-agent")
        regression-qs (resolve-regression-questions parameters)
        ;; Derived from the RESOLVED questions, never from the parameter. A
        ;; caller passing :regression-questions supplies no ids, and reading
        ;; the parameter would hand compute-batch-verdict an empty regression
        ;; set — it would skip every bystander check and still report a
        ;; verdict that looks guarded.
        regression-ids (mapv :id regression-qs)
        ;; ONE target question, golden = ALL enriched chunks. Per-chunk membership
        ;; is read from each run's top-20, so a single comparison scores the batch.
        target-q {:id target-id
                  :dataset :public-docs
                  :query user-query
                  :golden-chunk-ids chunk-ids
                  :grounding-mode :chunks+answer}
        questions (into [target-q] regression-qs)
        ;; `expansion-mode` sets the baseline for BOTH configs (enrichment is the
        ;; only delta) → marginal (:corpus-aware-2hop) vs absolute (:blind).
        base-sp {:builtin/query-planner {:enabled true :expansion-mode expansion-mode}}
        configs [{:id "enrichment-off"
                  :skill-graph-id :builtin/agent-rag-graph-bundled
                  :skill-params base-sp}
                 {:id "enrichment-on"
                  :skill-graph-id :builtin/agent-rag-graph-bundled
                  :skill-params (assoc base-sp :builtin/retrieval
                                       {:enrichment-types [enrichment-type]})}]
        {:keys [rows]} (eval-runner/run-comparison
                         {:configs configs
                          :questions questions
                          :repeats repeats
                          :execution-scope {:tenant tenant
                                            :dataset-config-key dataset-config-key
                                            :agent-id agent-id}})
        {:keys [verdicts batch-summary] :as v} (compute-batch-verdict
                                                 rows target-id chunk-ids regression-ids)]
    (skills/success-result
      {:verdicts verdicts
       :batch-summary batch-summary
       :batch v
       :rows rows
       :summary batch-summary})))

(def eval-sweep-skill
  {:metadata eval-sweep-metadata
   :execute execute-eval-sweep})

;; =============================================================================
;; Registration
;; =============================================================================

(defn register!
  []
  (skills/register-skill! eval-sweep-skill))

(register!)
