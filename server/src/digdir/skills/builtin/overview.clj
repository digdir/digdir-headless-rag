(ns digdir.skills.builtin.overview
  "AI-overview skill graph — a SHORT synthesised answer with inline citations,
   presented ahead of the underlying results rather than instead of them.

   Replaces the retired :builtin/simple-qa (issue #240). It is new product
   work, not a restoration: simple-qa was a plain RAG graph whose answer was
   whatever length the model felt like, and it named a graph that no longer
   exists.

   Three questions #240 asks to be ANSWERED rather than assumed. The answers
   are here because this namespace is where they are enforced:

   (a) ANSWER LENGTH AND SHAPE — at most three sentences, roughly 60-80 words,
       no preamble, every sentence carrying at least one [N] citation. No
       existing graph produces that. The agent-rag graph variants run a ReAct
       loop whose answer length is unbounded and whose cost is many LLM calls;
       :builtin/fact-checker produces a verdict on a claim, not an overview of
       a question. So this graph is single-pass — plan -> retrieve -> rerank ->
       gate -> synthesise -> finalize — with its own synthesis prompt
       (`overview-generation-prompt`) and its own brevity cap. Single-pass is
       also the latency answer: two LLM calls total (one planner, one
       synthesis), against the agent loop's many.

   (b) CITATION GRANULARITY — chunk-level, and load-bearing rather than
       decorative. :builtin/synthesis already numbers each context doc and
       builds `citation-index` = {N -> chunk-id}, so a [N] in the overview
       resolves to the exact chunk a reader can open in one hop. This graph
       makes that a hard requirement instead of a nicety: an overview that
       parses to zero citations, or that cites an index outside the supplied
       set, is SUPPRESSED (see `finalize-overview`). The format's whole value
       is one-hop checkability, so an uncheckable overview is worth less than
       no overview.

   (c) WHEN IT DECLINES — see `evaluate-evidence` and `finalize-overview`.
       The rule is structural and runs BEFORE the LLM call, so thin retrieval
       costs nothing and cannot be talked over. Stated plainly:

         The overview is produced only when the reranked evidence clears a
         floor. Otherwise the graph returns the underlying results with no
         overview at all, and names why.

       Pre-synthesis (no LLM call is made):
         :no-evidence      — retrieval/rerank produced no context docs.
         :too-few-sources  — fewer than :min-context-docs (default 2) context
                             docs. A one-source overview is an assertion, not
                             a synthesis.
         :uncorroborated   — fewer than :min-supporting-chunks (default 2)
                             chunks scoring within :support-score-ratio
                             (default 0.6) of the top rerank score. One spike
                             above a flat field is a single passage, not a
                             consensus.
         :below-score-floor — top rerank score below :min-top-score. OFF by
                             default (nil): rerank scores come straight from
                             the reranker service and are not on a stable,
                             corpus-independent scale, so a hardcoded absolute
                             floor would be a guess. Set it per dataset once
                             that dataset's scale is known.
         :single-document  — fewer than :min-distinct-documents distinct source
                             documents. Default 1, i.e. OFF, because a narrow
                             factual question can be legitimately answered from
                             one document. Raise it for datasets where
                             corroboration across documents is the point.

       Post-synthesis (the model answered; the answer is thrown away):
         :model-declined      — the model emitted the INSUFFICIENT sentinel, or
                                :builtin/synthesis's own insufficiency detector
                                fired.
         :uncited             — the answer parsed to zero citations.
         :invalid-citations   — the answer cited an index with no chunk behind
                                it.

   An overview that answers confidently on thin retrieval is the worst thing
   this repo can ship: it is the format most likely to be believed and least
   likely to be checked. Every default above is chosen to fail towards
   silence."
  (:require [clojure.string :as str]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.builtin.synthesis :as synthesis]
            [digdir.skills.templates.core :as templates]))

;; =============================================================================
;; Prompt
;; =============================================================================

(def insufficient-sentinel
  "Bare token the model is told to emit, alone, when the sources do not support
   a direct answer.

   A sentinel rather than free text on purpose: `synthesis/detect-insufficient-context`
   is a regex over the response, so it can only recognise the phrasings it was
   taught, in the languages it was taught them in. A sentinel is exact and
   language-independent. The regex detector still runs as a second net."
  "INSUFFICIENT")

(def overview-generation-prompt
  "Synthesis prompt for the AI overview.

   Deliberately NOT reachable from the dataset's `skills.synthesis.generation-prompt`.
   That value is layered into `:skill-params {:builtin/synthesis ...}` by
   `digdir.api.util/build-skill-params-from-config`, and
   `digdir.skills.graph.runner/resolve-step-parameters` ranks per-skill params
   ABOVE a step's own `:parameters` — so a dataset that sets a long-answer
   prompt would silently override an overview prompt written into the step and
   turn the overview back into an ordinary answer. Running this step under its
   own skill id (:builtin/overview-synthesis) is what keeps that from
   happening. The graph would look right in source and behave wrong in the
   artifact otherwise."
  (str "Write an AI overview: a direct answer to the question, drawn only from the numbered sources below.\n\n"
       "Rules:\n"
       "- At most three sentences. Aim for 60-80 words. Never exceed four sentences.\n"
       "- Start with the answer. No preamble, no restating the question, no \"based on the sources\".\n"
       "- Every sentence must carry at least one [N] citation.\n"
       "- Use only what the sources state. Do not add background the sources do not contain.\n"
       "- If the sources do not support a direct answer, reply with exactly " insufficient-sentinel
       " and nothing else. Do not guess, and do not answer partially.\n\n"
       "Sources:\n{context}\n\n"
       "Question: {question}\n\n"
       "Overview:"))

;; =============================================================================
;; Evidence gate — the pre-synthesis half of the decline rule
;; =============================================================================

(def default-gate-params
  "Defaults for the pre-synthesis evidence gate. See the namespace docstring
   for why each is set where it is."
  {:min-context-docs 2
   :min-supporting-chunks 2
   :support-score-ratio 0.6
   :min-distinct-documents 1
   :min-top-score nil})

(defn- chunk-document-id
  "Best-effort document identity for a retrieval chunk. Chunks are labelled
   differently by different code paths, so try the known spellings rather than
   silently counting every chunk as its own document."
  [chunk]
  (some #(let [v (get chunk %)]
           (when (or (string? v) (number? v)) v))
        [:doc_num :doc-num :document_id :document-id :doc_id]))

(defn evaluate-evidence
  "Decide whether the reranked evidence is thick enough to synthesise an
   overview from. Pure: no LLM call, no I/O.

   `chunks` are the reranked chunks (each may carry :rerank-score);
   `context-docs` are the docs that were actually assembled into context.

   Returns {:sufficient? bool :reason keyword|nil :summary map}. The summary is
   reported whether or not the gate passes, so a decline can be explained
   without re-running retrieval."
  [{:keys [chunks context-docs]} params]
  (let [{:keys [min-context-docs min-supporting-chunks support-score-ratio
                min-distinct-documents min-top-score]}
        (merge default-gate-params params)

        n-docs (count context-docs)
        scores (->> chunks (map :rerank-score) (filter number?) (map double))
        ;; Rerank can be disabled (:builtin/rerank {:enabled false}), in which
        ;; case chunks arrive without :rerank-score. Score-based gates are then
        ;; not evaluable — skip them rather than treating "no score" as "bad
        ;; score", which would decline every overview on such a dataset.
        scored? (boolean (seq scores))
        top-score (when scored? (apply max scores))
        supporting (when scored?
                     (count (filter #(>= % (* (double support-score-ratio) top-score))
                                    scores)))
        distinct-documents (->> chunks (keep chunk-document-id) distinct count)

        reason (cond
                 (zero? n-docs) :no-evidence
                 (< n-docs min-context-docs) :too-few-sources
                 (and scored? (number? min-top-score) (< top-score (double min-top-score)))
                 :below-score-floor
                 (and scored? (< supporting min-supporting-chunks)) :uncorroborated
                 (and (pos? distinct-documents)
                      (< distinct-documents min-distinct-documents)) :single-document
                 :else nil)]
    {:sufficient? (nil? reason)
     :reason reason
     :summary {:context-doc-count n-docs
               :scored? scored?
               :top-score top-score
               :supporting-chunk-count supporting
               :distinct-document-count distinct-documents
               :thresholds {:min-context-docs min-context-docs
                            :min-supporting-chunks min-supporting-chunks
                            :support-score-ratio support-score-ratio
                            :min-distinct-documents min-distinct-documents
                            :min-top-score min-top-score}}}))

(def evidence-gate-metadata
  {:skill-id :builtin/overview-evidence-gate
   :name "Overview evidence gate"
   :description "Decide, before any LLM call, whether retrieval is thick enough to justify an AI overview."
   :category :validation
   :inputs [:chunks :context-docs]
   ;; :gated-context-docs is the context the synthesis step consumes — the
   ;; incoming docs when the gate passes, empty when it declines. It exists so
   ;; the ordering dependency between :gate and :overview is a REAL input edge.
   ;; `digdir.skills.graph.runner/topological-sort` derives execution order from
   ;; `:inputs` refs only; a `:condition` function is opaque to it. An :overview
   ;; step that read its context straight from :rerank and leaned on the
   ;; condition alone would be sorted with no edge to :gate at all, could run
   ;; first, and would then read an empty step-outputs map — declining every
   ;; single overview while looking perfectly correct in source.
   :outputs [:evidence-sufficient? :evidence-decline-reason :evidence-summary
             :gated-context-docs]
   :parameters {:min-context-docs :number
                :min-supporting-chunks :number
                :support-score-ratio :number
                :min-distinct-documents :number
                :min-top-score :number}
   :version "1.0.0"
   :tags #{:overview :gating :production}})

(defn execute-evidence-gate
  [{:keys [inputs parameters] :as _ctx}]
  (let [{:keys [sufficient? reason summary]} (evaluate-evidence inputs parameters)]
    (skills/success-result
      {:evidence-sufficient? sufficient?
       :evidence-decline-reason reason
       :evidence-summary summary
       :gated-context-docs (if sufficient? (vec (:context-docs inputs)) [])}
      {:evidence-sufficient? sufficient?
       :evidence-decline-reason reason})))

(def evidence-gate-skill
  {:metadata evidence-gate-metadata
   :execute execute-evidence-gate})

;; =============================================================================
;; Overview synthesis — :builtin/synthesis under an overview-specific skill id
;; =============================================================================

(def overview-synthesis-metadata
  {:skill-id :builtin/overview-synthesis
   :name "Overview synthesis"
   :description "Generate a short, fully-cited AI overview from reranked context."
   :category :generation
   :inputs [:query :context-docs]
   :outputs [:overview-response :citations :citation-index :citation-validation
             :insufficient-context :insufficient-context-signal :prompts]
   :parameters {:model :string
                :temperature :number
                :max-tokens :number
                :system-prompt :string
                :generation-prompt :string
                :max-docs :number}
   :version "1.0.0"
   :tags #{:overview :llm :generation :production}})

(def default-synthesis-parameters
  "Brevity cap and prompt for the overview.

   :max-tokens is a backstop, not the mechanism — the prompt is what produces a
   three-sentence answer, and a token cap alone would produce a truncated long
   answer instead of a short complete one. It is set so a runaway generation
   cannot cost a long answer's tokens. `digdir.llm.model-params` renames it to
   `max_completion_tokens` for the GPT-5 family, which rejects `max_tokens`."
  {:generation-prompt overview-generation-prompt
   :max-tokens 400})

(defn execute-overview-synthesis
  "Run :builtin/synthesis's implementation with overview defaults, and rename
   its :response output to :overview-response.

   The rename is not cosmetic. `digdir.skills.graph.runner/collect-outputs`
   merges every step's outputs into one map, so two steps emitting :response
   would leave the graph's final :response decided by map iteration order.
   Exactly one step in this graph produces :response — :finalize — and it is
   the step that has seen the citation checks."
  [{:keys [parameters] :as ctx}]
  (let [result (synthesis/execute-synthesis
                 (assoc ctx :parameters (merge default-synthesis-parameters parameters)))
        outputs (skills/get-result-outputs result)]
    (if (skills/result-error? result)
      result
      (skills/success-result
        (-> outputs
            (assoc :overview-response (:response outputs))
            (dissoc :response))
        (skills/get-result-metadata result)))))

(def overview-synthesis-skill
  {:metadata overview-synthesis-metadata
   :execute execute-overview-synthesis})

;; =============================================================================
;; Finalize — the post-synthesis half of the decline rule
;; =============================================================================

(defn- sentinel-only?
  "True when the model emitted the INSUFFICIENT sentinel rather than an answer.
   Tolerates surrounding punctuation and whitespace so a model that writes
   \"INSUFFICIENT.\" is still understood to have declined."
  [response]
  (-> (or response "")
      str/trim
      (str/replace #"^[\p{Punct}\s]+|[\p{Punct}\s]+$" "")
      str/upper-case
      (= insufficient-sentinel)))

(defn finalize-overview
  "Apply the post-synthesis half of the decline rule and produce the graph's
   single :response.

   Pure. `evidence-sufficient?` false means the synthesis step was skipped and
   every synthesis-derived input is nil; that is the :no-evidence /
   :too-few-sources / :uncorroborated path and it is reported as-is."
  [{:keys [evidence-sufficient? evidence-decline-reason overview-response
           citations citation-index citation-validation insufficient-context]}]
  (let [reason (cond
                 (not evidence-sufficient?) (or evidence-decline-reason :no-evidence)
                 (str/blank? (str overview-response)) :model-declined
                 (sentinel-only? overview-response) :model-declined
                 (true? insufficient-context) :model-declined
                 (empty? citations) :uncited
                 (and (map? citation-validation)
                      (false? (:all-valid? citation-validation))) :invalid-citations
                 :else nil)]
    (if reason
      ;; A declined overview carries no citation index: there is no answer for
      ;; it to index, and leaving a populated one behind invites a surface to
      ;; render sources under an overview that was never shown.
      {:response ""
       :overview nil
       :overview-declined? true
       :overview-decline-reason reason
       :citations []
       :citation-index {}}
      {:response overview-response
       :overview overview-response
       :overview-declined? false
       :overview-decline-reason nil
       :citations (vec citations)
       ;; Survives to the caller: this is what turns a [N] in the text into a
       ;; chunk-id, which is the whole one-hop-checkability claim.
       :citation-index (or citation-index {})})))

(def overview-finalize-metadata
  {:skill-id :builtin/overview-finalize
   :name "Overview finalize"
   :description "Suppress an overview that is uncited, invalid, or declined; emit the graph's single :response."
   :category :validation
   :inputs [:evidence-sufficient? :evidence-decline-reason :overview-response
            :citations :citation-index :citation-validation :insufficient-context]
   ;; Everything downstream of the gate is nil when the gate declined and the
   ;; synthesis step was skipped. They are inputs for documentation, not
   ;; requirements.
   :optional-inputs [:overview-response :citations :citation-index
                     :citation-validation :insufficient-context]
   :outputs [:response :overview :overview-declined? :overview-decline-reason
             :citations :citation-index]
   :parameters {}
   :version "1.0.0"
   :tags #{:overview :gating :production}})

(defn execute-overview-finalize
  [{:keys [inputs] :as _ctx}]
  (let [out (finalize-overview inputs)]
    (skills/success-result
      out
      {:overview-declined? (:overview-declined? out)
       :overview-decline-reason (:overview-decline-reason out)
       :citation-count (count (:citations out))})))

(def overview-finalize-skill
  {:metadata overview-finalize-metadata
   :execute execute-overview-finalize})

;; =============================================================================
;; Graph
;; =============================================================================

(def ai-overview-graph
  "Single-pass overview: plan -> retrieve -> rerank -> gate -> synthesise -> finalize.

   The :overview step's `:condition` is what makes the decline cheap: when the
   gate says the evidence is thin, the graph runner skips the step entirely and
   no LLM call is made."
  {:id :ai-overview
   :name "AI Overview"
   :description "Short, fully-cited synthesised answer presented ahead of the underlying results."
   :inputs [:user-query :docs-collection :chunks-collection :phrases-collection
            :conversation-history]
   :outputs [:response :overview :overview-declined? :overview-decline-reason
             :citations :citation-index :chunks :context-docs :queries
             :user-intent :search-attribution :evidence-summary]
   :steps
   [{:id :plan
     :skill :builtin/query-planner
     :inputs {:query :$user-query
              :conversation-history :$conversation-history
              :phrases-collection :$phrases-collection}}

    {:id :retrieve
     :skill :builtin/retrieval
     :inputs {:queries [:plan :queries]
              :user-intent [:plan :user-intent]
              :docs-collection :$docs-collection
              :chunks-collection :$chunks-collection
              :phrases-collection :$phrases-collection}}

    {:id :rerank
     :skill :builtin/rerank
     :inputs {:chunks [:retrieve :chunks]
              :query :$user-query
              :docs-collection :$docs-collection}}

    {:id :gate
     :skill :builtin/overview-evidence-gate
     :inputs {:chunks [:rerank :chunks]
              :context-docs [:rerank :context-docs]}}

    ;; Context comes from :gate, not :rerank. That is what puts a real edge in
    ;; the topological sort — see :gated-context-docs on the gate's metadata.
    ;; The :condition is the second guard, not the only one: without the input
    ;; edge the runner is free to schedule this step before :gate has run.
    {:id :overview
     :skill :builtin/overview-synthesis
     :inputs {:query :$user-query
              :context-docs [:gate :gated-context-docs]}
     ;; Declarative ref, not a fn (#348). This is the same shape as the
     ;; `:context-docs` ref above and resolves through the same code path.
     ;; It was a `fn` only because the `:condition` slot did not accept a ref
     ;; until #348; a function object here is unserializable and blanked the
     ;; Skill Graphs admin screen, which is now the only way to see the
     ;; registered modes at all (#350 removed the listing endpoints).
     :condition [:gate :evidence-sufficient?]}

    {:id :finalize
     :skill :builtin/overview-finalize
     :inputs {:evidence-sufficient? [:gate :evidence-sufficient?]
              :evidence-decline-reason [:gate :evidence-decline-reason]
              :overview-response [:overview :overview-response]
              :citations [:overview :citations]
              :citation-index [:overview :citation-index]
              :citation-validation [:overview :citation-validation]
              :insufficient-context [:overview :insufficient-context]}}]})

(def ai-overview-skill-graph
  (templates/make-skill-graph
    :builtin/ai-overview
    "AI Overview"
    "Short synthesised answer with inline chunk-level citations, ahead of the underlying results. Declines rather than answering on thin retrieval."
    ai-overview-graph
    {:version "1.0.0"
     :tags #{:rag :overview :qa :production}
     :input-schema templates/agent-tool-input-schema}))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register!
  "Register the overview skills and the :builtin/ai-overview skill graph."
  []
  (skills/register-skill! evidence-gate-skill)
  (skills/register-skill! overview-synthesis-skill)
  (skills/register-skill! overview-finalize-skill)
  (templates/register-skill-graph! ai-overview-skill-graph))
