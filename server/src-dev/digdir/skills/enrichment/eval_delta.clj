(ns digdir.skills.enrichment.eval-delta
  "Phase A — in-process eval-suite skill (the 'thermometer').

   `:builtin/enrichment-eval-suite` runs an agent-budget-benchmark
   suite against the current code state and returns the structured
   result map (the same payload the `bb agent-budget-benchmark`
   CLI prints as canonical EDN to stdout).

   Designed for the self-improvement agent to ask 'did this proposed
   enrichment help?' without spawning a new JVM. Each invocation
   represents one full pass through the suite under a given
   `:graph-variant` and skill-params configuration.

   This namespace lives in `src-dev/` because it depends on
   `digdir.tools.diagnostics` (also in `src-dev/`). The skill registers
   itself at namespace-load time — production builds that don't include
   `src-dev` on the classpath won't see it. Tests, REPLs, and dev-time
   skill-driven workflows do.

   See `plans/in-progress/self-improvement-agent-plan.md` for the
   surrounding Phase A/B/C/D/E roadmap."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.tools.diagnostics :as diagnostics]))

;; =============================================================================
;; Skill metadata
;; =============================================================================

(def eval-suite-metadata
  {:skill-id :builtin/enrichment-eval-suite
   :name "Enrichment eval suite"
   :description "Run an agent-budget-benchmark suite in-process and return its structured result. Used by the self-improvement agent to A/B-test enrichment proposals against fixed fixtures without spawning a new JVM."
   :category :orchestration
   ;; `:environment` is intentionally NOT in :inputs even though
   ;; diagnostics still accepts it as a fallback for :dataset-config-key.
   ;; It's a leftover from before the tenant-config-key migration and
   ;; would cause the skill's required-input validator to reject callers
   ;; that pass an explicit :dataset-config-key (the modern path) without
   ;; also supplying a redundant :environment. Removing it here lets the
   ;; self-improve-agent's run_eval_delta tool just work (smoke trace
   ;; 2026-05-18T17-28-56 surfaced the regression). Callers can still
   ;; pass :environment in their inputs map if they want the override —
   ;; diagnostics destructures it from there.
   ;; Only the minimum required for diagnostics/agent-budget-benchmark.
   ;; The remaining fields (`:tenant-config-key`, `:runtime-config-key`,
   ;; `:graph-variant`, `:agent-id`, `:fail-on-gate?`, `:progress?`,
   ;; `:retrieval-params`) all have sensible defaults in diagnostics or
   ;; are simply omitted; listing them as `:inputs` would make the
   ;; runner's input validator reject callers (like the self-improve
   ;; graph's eval step) that don't wire every one.
   :inputs [:suite-file :tenant :dataset-config-key]
   :outputs [:summary :results :effective-params :dataset-ref :error]
   :parameters {}
   :version "1.0.0"
   :tags #{:diagnostics :enrichment :self-improve}})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- build-cli-opts
  "Build the cli-opts vector the diagnostics fn parses internally. Keeps
   the in-process call site honest by reusing the same parser the CLI
   path uses, so behavior diverges in only one spot (the entry point)."
  [{:keys [suite-file graph-variant tenant-config-key runtime-config-key
           fail-on-gate? progress?]}]
  (cond-> []
    suite-file (into ["--suite" suite-file])
    (some? fail-on-gate?) (into ["--fail-on-gate" (str (boolean fail-on-gate?))])
    progress? (into ["--progress" "true"])
    tenant-config-key (into ["--tenant-config-key" tenant-config-key])
    runtime-config-key (into ["--runtime-config-key" runtime-config-key])
    graph-variant (into ["--graph-variant" (name graph-variant)])))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-eval-suite
  "Invoke `diagnostics/agent-budget-benchmark` with a target map derived
   from the skill's inputs. Returns whatever the diagnostics fn returned
   — which since the Phase A patch is the same structured payload it
   used to only `prn` to stdout.

   On benchmark-gate-failure inside diagnostics, the inner throw is
   bubbled up by the diagnostics fn's outer try/catch as an
   error-shape map; on any other throwable the diagnostics fn emits
   `{:error {:message ... :type ...}}`. Either way the skill returns
   `success-result` — the *eval result* succeeded even if its gate
   didn't pass; the caller decides what to do with the summary."
  [{:keys [inputs]}]
  (let [{:keys [tenant dataset-config-key tenant-config-key
                runtime-config-key environment agent-id]} inputs
        cli-opts (build-cli-opts inputs)
        target {:tenant tenant
                :dataset-config-key dataset-config-key
                :tenant-config-key tenant-config-key
                :runtime-config-key runtime-config-key
                :environment environment
                :agent-id agent-id
                :cli-opts cli-opts}
        ;; Rebind diagnostics' `*in-process?*` so its `cleanup!` does
        ;; NOT release the shared Datahike connection at the end of
        ;; the benchmark. The connection is shared with config-db /
        ;; agent-loop / playground UI; releasing it inside a
        ;; long-running dev-server JVM was the cause of the
        ;; "Connection has been released" errors that started
        ;; appearing in playground traces once an agent invoked
        ;; run_eval_delta (smoke 2026-05-18T21:29).
        result (try
                 (binding [diagnostics/*in-process?* true]
                   (diagnostics/agent-budget-benchmark target))
                 (catch clojure.lang.ExceptionInfo e
                   ;; A `:benchmark-gate-failure true` ex-info means the
                   ;; suite ran cleanly but the gate failed. We surface
                   ;; the summary instead of treating it as a skill
                   ;; failure.
                   (if (-> e ex-data :benchmark-gate-failure)
                     {:summary (-> e ex-data :summary)
                      :gate-failed? true}
                     (throw e))))]
    (skills/success-result
      (or result {})
      {})))

(def eval-suite-skill
  {:metadata eval-suite-metadata
   :execute execute-eval-suite})

;; =============================================================================
;; Registration
;; =============================================================================

(defn register!
  "Register the eval-suite skill. Idempotent. Eagerly invoked at
   namespace load (below) so any caller that requires this ns gets the
   skill in the registry without an extra step."
  []
  (skills/register-skill! eval-suite-skill))

(register!)
