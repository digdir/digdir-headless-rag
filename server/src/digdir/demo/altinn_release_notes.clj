(ns digdir.demo.altinn-release-notes
  "S2 — Altinn Release-Notes Cross-Check demo scenario.

   Given a list of Altinn 3 release-note items in the user query, identify
   which existing doc pages on https://docs.altinn.studio likely need to be
   updated, and emit a structured TODO list.

   Pipeline (S2-A, MVP shape — no foreach support in the runner yet):

     :builtin/entity-extraction        ; release-notes text -> entities
     -> :docs/entities->queries        ; entities -> retrieval query strings
     -> :builtin/multi-retrieval       ; one search per entity, merge results
     -> :builtin/rerank                ; pick the strongest evidence
     -> :docs/release-todo-synthesis   ; structured TODO list via tool-forcing JSON

   The per-entity-rerank / per-page-summarization fan-out called for in the
   plan needs a :foreach step in the runner, tracked separately as S2-B.

   Mirrors the S7 pattern: one demo namespace owns the bridge skill, the
   structured-synthesis skill, the custom skill graph, the agent record,
   and the register!/seed-agents! wiring."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [digdir.llm.openai :as llm]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.templates.core :as templates]
            [digdir.llm.client :as openai]
            [taoensso.timbre :as timbre]))

;; =============================================================================
;; Bridge skill: entities -> query strings
;; =============================================================================

(def entities->queries-metadata
  {:skill-id :docs/entities->queries
   :name "Entities -> Queries (demo)"
   :description "Pure data adapter — projects entity-extraction's :entities maps into the vector of query strings that multi-retrieval expects. No LLM, no services."
   :category :augmentation
   :inputs [:entities]
   :outputs [:queries]
   :parameters {:include-types :vector
                :dedupe? :boolean}
   :version "1.0.0"
   :tags #{:demo :adapter}})

(defn execute-entities->queries
  [{:keys [inputs parameters]}]
  (let [{:keys [entities]} inputs
        {:keys [include-types dedupe?] :or {dedupe? true}} (or parameters {})
        keep? (if (seq include-types)
                (let [allowed (set (map (fn [t] (-> t name str/lower-case)) include-types))]
                  #(contains? allowed (some-> % :type str/lower-case)))
                (constantly true))
        texts (->> entities
                   (filter keep?)
                   (map :text)
                   (remove str/blank?))
        queries (vec (if dedupe? (distinct texts) texts))]
    (skills/success-result
     {:queries queries}
     {:input-entity-count (count entities)
      :output-query-count (count queries)})))

(def entities->queries-skill
  {:metadata entities->queries-metadata
   :execute execute-entities->queries})

;; =============================================================================
;; Shared: LLM driver for structured release-cross-check synthesis
;; =============================================================================

(def ^:private release-todo-tools
  "Forces the LLM to emit a structured per-page TODO list as a JSON tool call."
  [{:type "function"
    :function
    {:name "emitReleaseTodos"
     :description "Emit the structured per-page TODO list for the release-notes cross-check."
     :parameters
     {:type "object"
      :required ["todos"]
      :properties
      {:todos
       {:type "array"
        :description "One TODO per (existing doc page, triggering release-note item) pair. Each release-note item appears here OR in uncovered_items, never both. Only emit a TODO when the prior-art passages contain a page that clearly relates to the item's subject; otherwise route the item to uncovered_items."
        :items
        {:type "object"
         :required ["page_url" "release_item" "predicted_edit"]
         :properties
         {:page_url {:type "string"
                     :description "URL of the existing doc page that needs updating. MUST be a value taken verbatim from the `page=` field of one of the passage headers (e.g. \"/nb/altinn-studio/apps/...\"). Never use the chunk= field, and never invent a URL that does not appear in the passages."}
          :release_item {:type "string"
                         :description "The release-note item text (or a short reference) that triggers this edit."}
          :predicted_edit {:type "string"
                           :description "One-sentence description of the edit the maintainer should make."}
          :rationale {:type "string"
                      :description "Why this page is affected, citing prior-art sources by [N] index. Must reference at least one passage by [N]."}
          :confidence {:type "string"
                       :enum ["high" "medium"]
                       :description "How confident you are that this page actually needs this edit. high = an existing passage clearly contradicts or omits the release-note item; medium = the page is the right topical area but the specific detail isn't in the passage. If you'd want to mark a TODO as low, the item belongs in uncovered_items instead."}}}}
       :uncovered_items
       {:type "array"
        :items {:type "string"}
        :description "Release-note items whose subject is not covered by any retrieved passage. Each item appears here OR in todos, never both. These are candidates for new pages or for separate discovery."}
       :notes
       {:type "array"
        :items {:type "string"}
        :description "Cross-cutting observations about the release as a whole (e.g. terminology shifts, overlapping pages). Empty if none."}}}}}])

(defn- build-todo-prompt
  [release-notes-text context-yaml]
  (str "You are a documentation-maintenance assistant for Altinn 3. You receive "
       "a list of release-note items and a set of retrieved prior-art passages "
       "from https://docs.altinn.studio (and related digdir.no pages). Your job "
       "is to produce a per-page TODO list of edits that the docs need so they "
       "stay accurate after the release.\n\n"
       "Release-notes input:\n" release-notes-text "\n\n"
       "Prior-art context (each passage numbered for citation):\n"
       context-yaml "\n\n"
       "Emit the TODO list by calling the emitReleaseTodos tool. Rules:\n"
       "- Each release-note item MUST land in EXACTLY ONE of `todos` or `uncovered_items`. Never both. If you put an item in uncovered_items, do not also emit a TODO for it, and vice versa.\n"
       "- A release-note item belongs in `todos` only when the prior-art passages contain at least one page that clearly relates to the item's subject. If you cannot identify such a page from the passages provided, place the item in `uncovered_items` and move on — do not invent or guess a page_url.\n"
       "- One TODO per (page, release-note item) pair. Cite by [N] in the rationale.\n"
       "- Use the `page=` field from the passage header as page_url (it is the document URL on docs.altinn.studio or related digdir.no). NEVER use the chunk= field as page_url. NEVER fabricate a page_url that is not in the passages.\n"
       "- Mark confidence honestly: high only when an existing passage clearly contradicts or omits the release-note item; medium when the page is related but the specific detail is not in the passage; do not emit low-confidence TODOs — those belong in uncovered_items."))

(defn- extract-page-url
  "The reranker output stores per-doc metadata under a collection-named key
   (e.g. :website_documents_<hash>). Find the first nested map that exposes
   a :url and return it; nil if none."
  [doc]
  (some (fn [v]
          (when (and (map? v) (:url v))
            (:url v)))
        (vals doc)))

(defn- format-context-docs
  "Numbered passages with the document URL surfaced explicitly in the header.
   The LLM uses `page=` as the canonical page_url field for the structured
   TODO output — without it the model falls back to the chunk-id.

   When the optional `summaries` vector (one map per doc, index-aligned)
   contains a `:summary` for a given doc, the summary is inlined into that
   passage's header. Used by `:docs/release-cross-check-v2` which produces
   per-page summaries via a foreach step."
  ([context-docs] (format-context-docs context-docs nil))
  ([context-docs summaries]
   (let [summary-at (fn [i]
                      (some-> summaries (nth i nil) :summary))]
     (->> context-docs
          (take 16)
          (map-indexed (fn [i doc]
                         (let [url (extract-page-url doc)
                               chunk-id (some-> doc :metadata :source)
                               sum (summary-at i)]
                           (str "[" (inc i) "] "
                                (when url (str "page=" url "  "))
                                (when chunk-id (str "chunk=" chunk-id))
                                "\n"
                                (when-not (str/blank? sum)
                                  (str "summary: " sum "\n"))
                                (:page_content doc)))))
          (str/join "\n\n---\n\n")))))

(defn generate-release-todos
  "Call Azure OpenAI with the emitReleaseTodos tool forced. Returns
   {:todos parsed-map :raw-json string :model-used model} on success or
   {:error message} on failure.

   `:context-yaml` is the pre-formatted passages block; callers decide
   whether per-page summaries were inlined into it."
  [{:keys [release-notes-text context-yaml tenant temperature]
    :or {temperature 0.2}}]
  (cond
    (str/blank? release-notes-text)
    {:error "release-notes-text is required"}

    (str/blank? tenant)
    {:error "tenant is required for LLM credential lookup"}

    :else
    (let [model (if (llm/use-azure-openai tenant)
                  (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
                  (cfg/get {:tenant tenant} :services :azure-openai :model-name))
          prompt (build-todo-prompt release-notes-text context-yaml)
          request {:model model
                   :messages [{:role "user" :content prompt}]
                   :tools release-todo-tools
                   :tool_choice {:type "function" :function {:name "emitReleaseTodos"}}
                   :temperature temperature}
          response (try
                     (if (llm/use-azure-openai tenant)
                       (openai/create-chat-completion
                        request
                        {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
                         :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
                         :impl :azure})
                       (openai/create-chat-completion request))
                     (catch Throwable t
                       (timbre/warn t "release-todo-synthesis LLM call failed")
                       {:error (.getMessage t)}))]
      (if (:error response)
        response
        (let [json-args (some-> response :choices first :message :tool_calls first :function :arguments)]
          (if json-args
            {:todos (json/read-str json-args :key-fn keyword)
             :raw-json json-args
             :model-used model}
            {:error (str "Model returned no emitReleaseTodos call. Raw content: "
                         (-> response :choices first :message :content))}))))))

(defn- render-todos-markdown
  [{:keys [todos uncovered_items notes]}]
  (let [todo-md (fn [{:keys [page_url release_item predicted_edit rationale confidence]}]
                  (str "### " (or page_url "(unknown page)")
                       (when confidence (str " — _" confidence " confidence_"))
                       "\n"
                       "- **Release item:** " (or release_item "—") "\n"
                       "- **Predicted edit:** " (or predicted_edit "—") "\n"
                       (when-not (str/blank? rationale)
                         (str "- **Rationale:** " rationale "\n"))))]
    (str "# Release-notes cross-check TODO\n\n"
         (when (seq todos)
           (str/join "\n" (map todo-md todos)))
         (when (seq uncovered_items)
           (str "\n## Uncovered release-note items (likely need new pages)\n\n"
                (str/join "\n" (map #(str "- " %) uncovered_items))
                "\n"))
         (when (seq notes)
           (str "\n## Cross-cutting notes\n\n"
                (str/join "\n" (map #(str "- " %) notes))
                "\n")))))

;; =============================================================================
;; Structured-synthesis skill (used by docs/release-cross-check)
;; =============================================================================

(def release-todo-synthesis-metadata
  {:skill-id :docs/release-todo-synthesis
   :name "Release-Notes TODO Synthesis (demo)"
   :description "Synthesis-stage replacement that emits a structured per-page TODO list given release-notes input + retrieved context."
   :category :generation
   :inputs [:query :context-docs]
   :outputs [:response :todo-list]
   :parameters {:model :string
                :temperature :number}
   ;; Honest declaration — credentials resolved via cfg/get at use site;
   ;; check-required-services exempts the use-site-resolved-services set.
   :required-services #{:azure-openai}
   :version "1.0.0"
   :tags #{:demo :release-notes :synthesis}})

(defn- enforce-todo-xor-uncovered
  "Programmatic post-processor: any release_item that the model placed in
   `uncovered_items` is stripped from `todos`. Three layers of prompt
   reinforcement (rule + schema description + schema enum without :low) did
   not reliably enforce this; structural validation does. Returns the parsed
   todos map with deduped :todos and any moved items recorded under
   :enforced-moves so the trace shows what changed."
  [{:keys [todos uncovered_items] :as parsed}]
  (let [uncovered-set (set (or uncovered_items []))
        {kept-todos true moved-todos false}
        (group-by #(not (contains? uncovered-set (:release_item %))) (or todos []))]
    (cond-> (assoc parsed :todos (vec kept-todos))
      (seq moved-todos)
      (assoc :enforced-moves
             {:reason :item-also-in-uncovered
              :moved-release-items (mapv :release_item moved-todos)}))))

(defn execute-release-todo-synthesis
  "Synthesizes a per-page TODO list from release-notes text + reranked context.

   Optional input `:summaries` (index-aligned vector of `{:summary ... :key-points [...]}`
   maps) is consumed when present — the S2-B foreach-based graph wires it in
   from a per-page summarization step. When absent, behaviour matches S2-A."
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [query context-docs summaries]} inputs
        {:keys [temperature]} (or parameters {})
        tenant (or (:tenant skill-params) "digdir")
        context-yaml (format-context-docs context-docs summaries)
        result (generate-release-todos {:release-notes-text query
                                        :context-yaml context-yaml
                                        :tenant tenant
                                        :temperature (or temperature 0.2)})]
    (if (:error result)
      (skills/error-result :docs/release-todo-synthesis-failed
                           (:error result)
                           {:stage :docs/release-todo-synthesis})
      (let [enforced (enforce-todo-xor-uncovered (:todos result))]
        (skills/success-result
         {:response (render-todos-markdown enforced)
          :todo-list enforced}
         (cond-> {:model-used (:model-used result)
                  :context-doc-count (count context-docs)
                  :summary-count (count summaries)
                  :todo-count (count (:todos enforced))
                  :uncovered-count (count (:uncovered_items enforced))}
           (:enforced-moves enforced)
           (assoc :enforced-moves (:enforced-moves enforced))))))))

(def release-todo-synthesis-skill
  {:metadata release-todo-synthesis-metadata
   :execute execute-release-todo-synthesis})

;; =============================================================================
;; Custom skill graph
;; =============================================================================

(def release-cross-check-graph
  "entity-extraction -> entities->queries -> multi-retrieval -> rerank -> release-todo-synthesis."
  {:id :docs/release-cross-check
   :name "Demo Release-Notes Cross-Check"
   :description "Given a release-notes list, identify existing doc pages that need updating."
   :inputs [:user-query :docs-collection :chunks-collection :phrases-collection :conversation-history]
   :outputs [:response :todo-list :chunks]
   :steps [{:id :extract
            :skill :builtin/entity-extraction
            :inputs {:text :$user-query}
            :parameters {:entity-types ["product" "feature" "api" "version" "concept"]
                         :max-entities 12}}
           {:id :to-queries
            :skill :docs/entities->queries
            :inputs {:entities [:extract :entities]}
            :parameters {:dedupe? true}}
           {:id :retrieve
            :skill :builtin/multi-retrieval
            :inputs {:queries [:to-queries :queries]
                     :docs-collection :$docs-collection
                     :chunks-collection :$chunks-collection
                     :phrases-collection :$phrases-collection}
            :parameters {:merge-strategy :union
                         :total-limit 60}}
           {:id :rerank
            :skill :builtin/rerank
            :inputs {:chunks [:retrieve :chunks]
                     :query :$user-query
                     :docs-collection :$docs-collection}}
           {:id :synthesize
            :skill :docs/release-todo-synthesis
            :inputs {:query :$user-query
                     :context-docs [:rerank :context-docs]}}]})

(def release-cross-check-skill-graph
  (templates/make-skill-graph
   :docs/release-cross-check
   "Demo Release-Notes Cross-Check"
   "Entity-extraction-driven retrieval over Altinn docs, producing a per-page TODO list."
   release-cross-check-graph
   {:version "1.0.0"
    :tags #{:demo :release-notes}
    :input-schema templates/agent-tool-input-schema}))

;; --- v2: same scenario, real per-page summarization via :foreach -------------
;;
;; Identical retrieval + rerank stack as v1, but with a fan-out summarization
;; step inserted before synthesis. Each reranked page is summarized in its own
;; LLM call; the synthesis stage then sees both the raw passages and a typed
;; per-page summary. This is the demo exemplar for the :foreach step type in
;; the runner.
(def release-cross-check-graph-v2
  {:id :docs/release-cross-check-v2
   :name "Demo Release-Notes Cross-Check (v2, foreach)"
   :description "Same as v1 but with per-page summarization via :foreach before synthesis."
   :inputs [:user-query :docs-collection :chunks-collection :phrases-collection :conversation-history]
   :outputs [:response :todo-list :chunks :per-page-summaries]
   :steps [{:id :extract
            :skill :builtin/entity-extraction
            :inputs {:text :$user-query}
            :parameters {:entity-types ["product" "feature" "api" "version" "concept"]
                         :max-entities 12}}
           {:id :to-queries
            :skill :docs/entities->queries
            :inputs {:entities [:extract :entities]}
            :parameters {:dedupe? true}}
           {:id :retrieve
            :skill :builtin/multi-retrieval
            :inputs {:queries [:to-queries :queries]
                     :docs-collection :$docs-collection
                     :chunks-collection :$chunks-collection
                     :phrases-collection :$phrases-collection}
            :parameters {:merge-strategy :union
                         :total-limit 60}}
           {:id :rerank
            :skill :builtin/rerank
            :inputs {:chunks [:retrieve :chunks]
                     :query :$user-query
                     :docs-collection :$docs-collection}}
           {:id :summarize-per-page
            :foreach {:over [:rerank :context-docs]
                      :as :page}
            :do {:skill :builtin/summarization
                 :inputs {:content :$page}
                 :parameters {:style :technical
                              :max-length 60
                              :bullet-points false}}
            :collect-as :per-page-summaries
            :on-error :default}
           {:id :synthesize
            :skill :docs/release-todo-synthesis
            :inputs {:query :$user-query
                     :context-docs [:rerank :context-docs]
                     :summaries [:summarize-per-page :per-page-summaries]}}]})

(def release-cross-check-skill-graph-v2
  (templates/make-skill-graph
   :docs/release-cross-check-v2
   "Demo Release-Notes Cross-Check (v2, foreach)"
   "v1 with per-page summarization fanned out via the runner's :foreach step type."
   release-cross-check-graph-v2
   {:version "2.0.0"
    :tags #{:demo :release-notes :foreach}
    :input-schema templates/agent-tool-input-schema}))

;; =============================================================================
;; Agent definition
;; =============================================================================

;; =============================================================================
;; Registration
;; =============================================================================
;; Per-demo agents removed in Phase 0 — :builtin/docs-agent (agents/core.clj)
;; now owns docs/release-cross-check{,-v2} along with the other docs/* graphs.

(defn register!
  "Register the demo's bridge skill, structured-synthesis skill, and both
   skill-graph variants (v1 flat, v2 foreach). Idempotent."
  []
  (skills/register-skill! entities->queries-skill)
  (skills/register-skill! release-todo-synthesis-skill)
  (templates/register-skill-graph! release-cross-check-skill-graph)
  (templates/register-skill-graph! release-cross-check-skill-graph-v2))
