(ns digdir.skills.enrichment.questions-graph
  "`:docs/enrich-one-chunk` — the per-chunk hypothetical-questions
   enrichment sub-graph.

   Promoted from `src-dev/digdir/demo/self_improve_graph.clj` for slice 1
   of #82 (#89). It is deliberately the INNER graph only: fetch the chunk,
   propose questions, apply them to a parallel enrichment collection, and
   verify retrieval. It does not evaluate, and it names no skill that
   reaches `digdir.sweep.*` or `digdir.tools.diagnostics`.

   The OUTER `:docs/self-improve-graph` embedded measurement as a step: its
   `:batch-eval` was `:builtin/enrichment-eval-sweep`, which required
   `digdir.sweep.runner` and `digdir.sweep.questions`. Slice 2 separated the
   two — the eval skill now lives in src/ and runs the comparison through
   `digdir.skills.enrichment.eval-runner`, so an evaluated enrichment path no
   longer implies a research harness. The outer graph remains in src-dev for
   an unrelated reason: four of its other steps are still src-dev skills.

   Storage contract, unchanged by the promotion: enrichments live in a
   parallel collection keyed by `chunk_id`; base collections are never
   mutated, so a bad run is discardable rather than destructive."
  (:require [digdir.skills.templates.core :as templates]
            ;; Force registration of the four skills this graph steps
            ;; through. The runner resolves them by id at execution time,
            ;; so the namespaces must be loaded for the graph to run.
            [digdir.skills.enrichment.fetch-chunk-context]
            [digdir.skills.enrichment.propose-questions]
            [digdir.skills.enrichment.apply-questions]
            [digdir.skills.enrichment.verify-retrieval]))

(def enrich-one-chunk-graph
  "Per-chunk sequence: fetch chunk content, propose hypothetical
   questions, apply them to the enrichment collection, run the eval
   suite, and either keep or revert based on the gate.

   v1 wiring notes:

   - `:fetch` is the per-chunk content lookup. Keeping it inside the
     sub-graph (vs. having the outer graph pre-fetch) keeps the
     sub-graph self-contained — the outer graph just iterates
     chunk-ids.
   - `:apply` receives the propose step's whole outputs map via the
     singular `:proposal` input. The propose output's shape
     (`{:chunk-id :questions :provenance}`) matches what apply expects
     for one proposal; apply normalises singular→vec internally.
   - `:eval` runs the full eval suite. Costly, but correct: each
     chunk's gate verdict reflects the eval over the WHOLE corpus
     with this chunk's enrichment in place. Faster per-chunk
     approximations are a future optimisation.
   - `:decide` dispatches on `[:eval :gate-pass]`. We rely on the
     top-level `:gate-pass` mirror that eval-delta emits — the runner's
     2-element ref form can't reach into `[:summary :gate-pass]`
     directly.
   - `:revert` deletes by `chunk-id` only (no prompt-hash narrowing).
     This is safe because the outer graph's `:analyze` step defaults
     to `:exclude-already-enriched? true`, so a graph run only ever
     touches chunks that started with no enrichment rows — there's
     nothing else for the revert to accidentally wipe."
  {:id :docs/enrich-one-chunk
   :inputs [:chunk-id
            :enrichment-collection-name
            :chunks-collection
            :docs-collection
            :tenant
            :dataset-config-key
            :tenant-config-key
            :runtime-config-key
            :suite-file
            :user-query]
   :outputs [:chunk-id :proposal :verify]
   :steps
   [{:id :fetch
     :skill :builtin/enrichment-fetch-chunk-context
     :inputs {:chunk-id :$chunk-id
              :tenant :$tenant
              :chunks-collection :$chunks-collection
              :docs-collection :$docs-collection}}

    {:id :propose
     :skill :builtin/enrichment-propose-questions
     :inputs {:chunk-id :$chunk-id
              :chunk-content [:fetch :chunk-content]
              :doc-title [:fetch :doc-title]
              :doc-url [:fetch :doc-url]}}

    {:id :apply
     :skill :builtin/enrichment-apply-questions
     ;; `:doc-num` is required by the Typesense schema
     ;; (`{:name "doc_num" :optional false}` in
     ;; `digdir.skills.enrichment.collections/hypothetical-questions-schema`).
     ;; propose-questions doesn't emit it; threading it from the fetch
     ;; step is the cleanest fix. Without this, the upsert fails per-row
     ;; with "Error with field `doc_num`: Value cannot be empty." and
     ;; apply-questions surfaces the failure (previously it was
     ;; silently masked behind `applied-count (count rows)`).
     :inputs {:proposal :propose
              :doc-num [:fetch :doc-num]
              :collection-name :$enrichment-collection-name}}

    ;; BATCH (P2): the inner sub-graph now PROPOSES + APPLIES only. The expensive
    ;; per-chunk eval benchmark and per-chunk decide/revert moved OUT to the outer
    ;; graph's `:batch-eval` (one eval-sweep over all chunks) + `:batch-decide`.
    ;; `:verify` stays as the CHEAP raw-lookup SHADOW signal (runs without
    ;; `:eval-summary` → its `:improved?` is the lookup-only signal we compare
    ;; against the batch verdict in the report).
    {:id :verify
     :skill :builtin/enrichment-verify-retrieval
     :inputs {:chunk-id :$chunk-id
              :enrichment-collection-name :$enrichment-collection-name
              :docs-collection :$docs-collection
              :chunks-collection :$chunks-collection
              :user-query :$user-query}
     :parameters {:enrichment-type :hypothetical-questions}}

    ;; (no per-chunk :decide — batch-decide on the outer graph handles revert)
    ]})

(def enrich-one-chunk-skill-graph
  (templates/make-skill-graph
   :docs/enrich-one-chunk
   "Self-improve — enrich one chunk"
   "Per-chunk inner sub-graph for the self-improve experiment: propose hypothetical questions, apply them, run the eval suite, and decide whether to keep or revert based on the gate."
   enrich-one-chunk-graph
   {:version "1.0.0"
    :tags #{:demo :self-improve :enrichment :graph-only}}))

(defn register!
  "Register the per-chunk enrichment sub-graph. Idempotent.

   Called on namespace load AND explicitly from
   digdir.skills.init/initialize!, because a load-only registration cannot
   come back after skills-api/reset-skills! empties the registry (a second
   require is a no-op) — the failure mode the #71 gate caught."
  []
  ;; Register the skills this graph dispatches to, not just the graph itself.
  ;; Requiring their namespaces registers them at load time, but a second
  ;; require after a registry reset is a no-op - so a graph could be registered
  ;; while its skills could not resolve, and the failure surfaced at invocation
  ;; instead of registration (#91). The thing that names a dependency is the
  ;; thing that pulls it in.
  (digdir.skills.enrichment.fetch-chunk-context/register!)
  (digdir.skills.enrichment.propose-questions/register!)
  (digdir.skills.enrichment.apply-questions/register!)
  (digdir.skills.enrichment.verify-retrieval/register!)
  (templates/register-skill-graph! enrich-one-chunk-skill-graph))

(register!)
