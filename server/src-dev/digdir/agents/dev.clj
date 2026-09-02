(ns digdir.agents.dev
  "Agent surface that exists only where `src-dev` is on the classpath.

   Since slice 1 of #82 (#89), `builtin/docs-agent` itself lives in
   `digdir.agents.core` and ships in production, opt-in. What remains here
   is the part production has no code for: the three src-dev-only
   self-improve graphs, and the widening that lets the agent offer them in
   a dev build.

   The requires below are the point of this namespace, not incidental. An
   agent names its graphs as *strings*, so nothing in the compiler or in
   `digdir.build.src-dev-boundary-test` (which parses ns forms) can notice
   when a definition and its graphs drift apart — that is exactly how #71
   shipped. Keeping the graph requires next to the widening that references
   them makes the pair inseparable: wherever this namespace loads, those
   graphs are registered, and where it does not, nothing names them.

   So: if you add a graph id to `dev-agent-graph-additions`, add its require
   here."
  (:require
   ;; Force registration of the src-dev-only graphs named below. Each
   ;; registers itself on namespace load and also exposes an idempotent
   ;; register! — see `register-graphs!`.
   [digdir.demo.self-improve-graph :as self-improve-graph]
   [digdir.demo.self-improve-facts-graph :as self-improve-facts-graph]
   [digdir.demo.self-improve-phrases-graph :as self-improve-phrases-graph]))

(def dev-agent-definitions
  "Agents that exist only in a dev build.

   Empty since #89: `builtin/docs-agent` moved to production. Kept as the
   extension point — `digdir.agents.core` reads it whenever this namespace
   is loadable — so adding a dev-only agent needs no change in src/."
  [])

(def dev-agent-graph-additions
  "Extra `:allowed-skill-graphs` merged into an existing production agent
   when this namespace loads, keyed by agent id.

   Widening only: this never introduces an agent and never changes a
   default, so there is exactly one definition of `builtin/docs-agent` and a
   dev build simply offers it more graphs than a production build has code
   for.

   `docs/self-improve-graph` is the full corpus loop, whose `:batch-eval`
   step reaches the research harness (`digdir.sweep.runner`,
   `digdir.sweep.questions`) — which is why it stays dev-only and only the
   inner `docs/enrich-one-chunk` was promoted. Separating measurement from
   enrichment is slice 2."
  {"builtin/docs-agent" ["docs/self-improve-graph"
                         "docs/self-improve-facts-graph"
                         "docs/self-improve-phrases-graph"]})

(defn register-graphs!
  "Re-register the src-dev-only graphs referenced above. Idempotent.

   Loading this namespace registers them once, as a side effect. That is not
   enough on its own: `skills-api/reset-skills!` empties the registry, and a
   second `require` is a no-op, so a load-only registration can never come
   back — the graphs would stay missing for the rest of the process. The
   src/ graphs avoid this because `digdir.skills.init/initialize!` calls
   their `register!` explicitly; this gives the src-dev graphs the same
   treatment.

   Found by the resolvability gate in digdir.skills.init-test, which passed
   alone and failed in the full suite precisely because an earlier test had
   reset the registry."
  []
  (self-improve-graph/register!)
  (self-improve-facts-graph/register!)
  (self-improve-phrases-graph/register!))
