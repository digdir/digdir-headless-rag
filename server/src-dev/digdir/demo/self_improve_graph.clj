(ns digdir.demo.self-improve-graph
  "Phase C (graph variant) — `:docs/self-improve-graph`.

   Parallel to the ReAct `:docs/self-improve-agent`, this is the
   skill-graph version: same job (grow the hypothetical-questions
   enrichment collection, gated by eval-delta), but the control flow
   is graph-structured rather than LLM-decided.

   See `plans/proposed/self-improve-graph-plan.md` for the surrounding
   motivation: the two LLM-non-determinism failure modes the ReAct
   version exhibits (skip-propose, terminal-denial) become structurally
   impossible when the graph encodes the propose→apply→eval→decide
   sequence as edges.

   This namespace defines the OUTER graph (analyze + foreach +
   compose-report). The INNER per-chunk sub-graph was promoted to
   `digdir.skills.enrichment.questions-graph` in src/ (slice 1 of #82,
   see #89); this namespace requires it and re-registers it.

   The outer graph stays here, but no longer for that reason. Slice 2b of
   #82 (#94) moved `:builtin/enrichment-eval-sweep` to src/ and off the
   research harness, so the `:batch-eval` step is now deployable. What still
   holds this graph in src-dev is the rest of its steps —
   `:builtin/enrichment-analyze-corpus`, `:builtin/enrichment-batch-decide`,
   `:builtin/enrichment-compose-report` and `:builtin/extract-user-intent`
   all live in src-dev. That is a promotion question, not a harness one.

   The outer foreach uses the runner's native sub-graph dispatch
   (added 2026-05-19). Previously this required a thin
   `:builtin/run-sub-graph` dispatcher skill — removed once the runner
   gained foreach-:do-:sub-graph support and auto-forwarded tenant +
   config-keys to the child via `propagated-execution-keys` in
   runner.clj.

   Lives in `src-dev/` because the enrichment skills it composes do
   too. Production builds without src-dev on the classpath simply
   don't see these graphs."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.skills.templates.core :as templates]
            ;; Force registration of the enrichment skills the graph
            ;; composes. The graph runner looks them up by id at
            ;; step-execution time; requiring the namespaces makes them
            ;; live.
            [digdir.skills.builtin.query-planner]
            [digdir.skills.enrichment.analyze-corpus]
            [digdir.skills.enrichment.compose-report]
            ;; eval-delta was required here but stepped through by NO step of
            ;; either graph in this file — dead weight, dropped in slice 2a
            ;; (#94). It is the sole requirer of digdir.tools.diagnostics.
            [digdir.skills.enrichment.eval-sweep]
            ;; Installs the fixture-backed regression-question source that
            ;; eval-sweep exposes as an empty seam in a production build.
            [digdir.skills.enrichment.eval-sweep-fixture]
            [digdir.skills.enrichment.batch-decide]
            [digdir.skills.enrichment.extract-intent]
            [digdir.skills.enrichment.mark-keep]
            [digdir.skills.enrichment.revert-chunk]
            ;; The inner per-chunk sub-graph this outer graph dispatches to
            ;; was promoted to src/ in slice 1 (#89). Requiring it both
            ;; registers :docs/enrich-one-chunk and pulls in the four
            ;; enrichment skills it steps through.
            [digdir.skills.enrichment.questions-graph :as questions-graph]))

;; =============================================================================
;; Outer graph: self-improve over the whole corpus
;; =============================================================================

(def self-improve-graph
  "Top-level skill graph for the self-improve experiment:
   `analyze` (pick chunks) → `foreach` (per-chunk sub-graph) →
   `compose-report` (deterministic Markdown + structured stats).

   The foreach iterates over `[:analyze :chunk-ids]` and invokes the
   inner sub-graph via the `:builtin/run-sub-graph` dispatcher. That
   indirection exists only because the runner's `ForeachStep` schema
   restricts `:do` to a single-skill map (no `:sub-graph` directly);
   the dispatcher forwards the iteration's inputs verbatim, so the
   per-chunk trace still reads cleanly.

   v1 input contract: the caller resolves the four collection names
   (docs / chunks / enrichment) and the eval suite-file outside the
   graph and passes them in. Keeping that resolution out of the graph
   means a single playground agent can run against different
   tenant/dataset configurations by just changing inputs."
  {:id :docs/self-improve-graph
   ;; Only :docs-collection and :chunks-collection are listed as
   ;; required graph inputs because those are what the playground
   ;; reliably threads. Tenant + the config-key trio are passed via
   ;; skill-params (not graph inputs) and reach downstream skills that
   ;; way. The enrichment-collection-name is derived inside the analyze
   ;; step from docs-collection. Suite-file is a literal default below.
   :inputs [:docs-collection :chunks-collection :user-query]
   :outputs [:report :report-structured :chunk-outcomes :analysis]
   :steps
   [;; First: extract intent from the raw user prompt. The user might
    ;; write a noisy, English-language instruction (\"For chunk X,
    ;; propose 4 questions...\") over a Norwegian corpus. This step
    ;; normalises that into:
    ;;   :topic              — clean topical phrase in corpus language
    ;;                         (used as the content-search query)
    ;;   :explicit-chunk-ids — chunk_ids the user named verbatim
    ;;                         (analyze short-circuits to these when
    ;;                         present, skipping search+rank entirely)
    ;;   :goal               — one-line summary for the report
    {:id :intent
     :skill :builtin/extract-user-intent
     :inputs {:user-query :$user-query}}

    ;; D2.20 — Expand the intent topic into a small batch of relaxed
    ;; queries so analyze-corpus can sample a more diverse candidate
    ;; pool. Same `:builtin/query-planner` skill the retrieval pipeline
    ;; uses; outputs a `:queries` vec. Bumping max-phrases above the
    ;; default (~6) is wasted unless the LLM has clear angles to vary.
    {:id :plan-queries
     :skill :builtin/query-planner
     ;; `:conversation-history` is declared required in query-planner's
     ;; metadata but the body treats it as optional. Passing an empty
     ;; vec literal satisfies the runner's input validator without
     ;; changing other callers' behaviour.
     :inputs {:query [:intent :topic]
              :conversation-history []}
     :parameters {:max-phrases 6}}

    {:id :analyze
     :skill :builtin/enrichment-analyze-corpus
     :inputs {:docs-collection :$docs-collection
              :chunks-collection :$chunks-collection
              :max-chunks 10
              :exclude-already-enriched? true
              ;; Use the cleaned topic for content-search (not the
              ;; noisy raw user-query). Falls back to user-query if
              ;; intent extraction returned nil/empty.
              :user-query [:intent :topic]
              ;; D2.19/D2.20 — relaxed queries from plan-queries drive
              ;; multi-pass sampling; analyze-corpus dedupes across
              ;; passes. Bigger / more topically-diverse candidate
              ;; pool than the single-query sample we had before.
              :queries [:plan-queries :queries]
              ;; If the user named specific chunks, honour them and
              ;; skip selection. Empty vec → fall through to LLM-rank.
              :explicit-chunk-ids [:intent :explicit-chunk-ids]}
     ;; LLM-rank candidates by topical relevance to the user's query.
     ;; `:selection-mode` is a PARAMETER (literal), not an input —
     ;; otherwise the runner treats the `:llm` keyword as a step-ref
     ;; and rejects the graph at validation time. Degrades to the
     ;; alphabetical-first heuristic if the LLM call errors or returns
     ;; no parseable chunk_ids (see analyze-corpus/llm-select-chunk-ids).
     :parameters {:selection-mode :llm}}

    {:id :per-chunk
     :foreach {:over [:analyze :chunk-ids]
               :as :chunk-id}
     ;; Native foreach-sub-graph dispatch (added 2026-05-19). Replaces
     ;; the earlier `:builtin/run-sub-graph` dispatcher skill that
     ;; existed only because the foreach schema used to require a
     ;; single-skill `:do`. The runner now auto-forwards :tenant +
     ;; config-key trio + :agent-id from execution-opts into the
     ;; child's graph-inputs (see `propagated-execution-keys`), so the
     ;; sub-graph's `:$tenant`/`:$dataset-config-key` refs resolve.
     :do {:sub-graph {:graph-id :docs/enrich-one-chunk
                      :inputs {:chunk-id :$chunk-id
                               ;; Threaded from analyze's output — the
                               ;; playground doesn't supply enrichment
                               ;; collection name directly.
                               :enrichment-collection-name [:analyze :enrichment-collection-name]
                               :chunks-collection :$chunks-collection
                               :docs-collection :$docs-collection
                               ;; Literal — playground doesn't pass
                               ;; this. Path is relative to server cwd.
                               :suite-file "test/fixtures/agent/altinn3_lansert_stability.edn"
                               ;; D2.8 — thread intent topic so the
                               ;; verify step inside the sub-graph can
                               ;; confirm the new enrichment actually
                               ;; creates a retrieval path for the
                               ;; user's query. (`:enrichment-type` is
                               ;; pinned on the verify step itself as a
                               ;; parameter so the runner doesn't try
                               ;; to resolve the literal keyword as a
                               ;; step-ref.)
                               :user-query [:intent :topic]}}}
     :collect-as :chunk-outcomes
     ;; A single chunk's failure (e.g. its eval blew up) should NOT
     ;; abort the whole self-improve pass. `:default` keeps an empty
     ;; outcome in that slot so the report can still surface a meaningful
     ;; summary of the rest of the run.
     :on-error :default}

    ;; BATCH (P2) — evaluate ALL freshly-applied enrichments in ONE sweep, then
    ;; revert the failures. Replaces the per-chunk eval/decide (which cost ~12
    ;; agent runs PER CHUNK); this costs ~(1+|reg|)×2×N for the whole run.
    {:id :batch-eval
     :skill :builtin/enrichment-eval-sweep
     ;; tenant/dataset-config-key arrive via skill-params (the eval-sweep skill
     ;; falls back to them); :user-query is the run's motivating query; the
     ;; foreach :chunk-outcomes carry the enriched chunk-ids.
     :inputs {:user-query :$user-query
              :chunk-outcomes [:per-chunk :chunk-outcomes]}
     :parameters {:repeats 3
                  :enrichment-type :hypothetical-questions
                  ;; One innocent-bystander query (broad-dilution guard).
                  :regression-question-ids ["altinn-dialogporten-about"]}}

    {:id :batch-decide
     :skill :builtin/enrichment-batch-decide
     :inputs {:verdicts [:batch-eval :verdicts]
              :chunk-outcomes [:per-chunk :chunk-outcomes]
              :enrichment-collection-name [:analyze :enrichment-collection-name]}}

    {:id :compose
     :skill :builtin/enrichment-compose-report
     ;; Outcomes now come from :batch-decide (each foreach outcome merged with its
     ;; :decision + :batch-verdict; the per-chunk :verify shadow is still on each).
     :inputs {:analysis :analyze
              :outcomes [:batch-decide :outcomes]}}]})

(def self-improve-skill-graph
  (templates/make-skill-graph
   :docs/self-improve-graph
   "Self-improve (graph variant)"
   "Graph-structured counterpart to the retired ReAct self-improve-agent. Analyzes the corpus, runs the per-chunk propose→apply→eval→decide sub-graph over a small batch of chunks, and emits a deterministic Markdown report."
   self-improve-graph
   {:version "1.0.0"
    :tags #{:demo :self-improve :enrichment :graph-only}
    :input-schema templates/agent-tool-input-schema}))

;; =============================================================================
;; Playground agent definition
;; =============================================================================

;; =============================================================================
;; Registration
;; =============================================================================
;; Per-demo agent removed in Phase 0 — :builtin/docs-agent (agents/core.clj)
;; owns docs/self-improve-graph along with the other docs/* skill graphs.

(defn register!
  "Register both the inner sub-graph and the outer graph. Idempotent —
   requiring this namespace eagerly invokes this fn so any caller that
   loads the ns gets both graphs in the templates registry."
  []
  ;; Register the skills this graph dispatches to, not just the graph itself.
  ;; Requiring their namespaces registers them at load time, but a second
  ;; require after a registry reset is a no-op - so a graph could be registered
  ;; while its skills could not resolve, and the failure surfaced at invocation
  ;; instead of registration (#91). The thing that names a dependency is the
  ;; thing that pulls it in.
  (digdir.skills.builtin.query-planner/register!)
  (digdir.skills.enrichment.analyze-corpus/register!)
  (digdir.skills.enrichment.compose-report/register!)
  (digdir.skills.enrichment.eval-sweep/register!)
  (digdir.skills.enrichment.batch-decide/register!)
  (digdir.skills.enrichment.extract-intent/register!)
  (digdir.skills.enrichment.mark-keep/register!)
  (digdir.skills.enrichment.revert-chunk/register!)
  ;; brings :docs/enrich-one-chunk and, now, its four skills
  (questions-graph/register!)
  (templates/register-skill-graph! self-improve-skill-graph))

(register!)

;; =============================================================================
;; Graph reachability check (development assistance)
;; =============================================================================

(defn all-required-skills-present?
  "Returns true iff every skill-id referenced by the inner sub-graph is
   registered. Useful for the test suite to fail fast with a clear
   message when a required skill ns isn't loaded — better than the
   runner's runtime 'skill not found' which can be confusing inside a
   sub-graph trace."
  []
  (let [referenced #{:builtin/enrichment-fetch-chunk-context
                     :builtin/enrichment-propose-questions
                     :builtin/enrichment-apply-questions
                     :builtin/enrichment-verify-retrieval
                     ;; batch eval + decide live on the OUTER graph now
                     :builtin/enrichment-eval-sweep
                     :builtin/enrichment-batch-decide
                     :builtin/enrichment-revert-chunk}]
    (every? #(some? (skills/get-skill %)) referenced)))
