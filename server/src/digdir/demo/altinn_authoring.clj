(ns digdir.demo.altinn-authoring
  "S7 — Altinn Authoring Assistant demo scenario.

   Demonstrates two flavors of customization side-by-side, sharing the same
   underlying LLM-driven outline generator:

   - `demo/altinn-authoring` (flavor 3): agent over the built-in agent-rag
     skill graph with `propose_outline` registered as an *optional* ReAct
     tool. The LLM may or may not call it.

   - `demo/altinn-outline-graph` (flavor 2): agent over a custom skill graph
     `docs/outline-graph` whose synthesis stage is the propose-outline skill
     itself. The outline tool fires every time as a pipeline step.

   Same prompt against the two agents lets the demo compare:
   freeform-but-optional-tool vs. mandatory-structured-synthesis."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [digdir.llm.openai :as llm]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.builtin.agent.tools :as agent-tools]
            [digdir.skills.templates.core :as templates]
            [digdir.llm.client :as openai]
            [taoensso.timbre :as timbre]))

;; =============================================================================
;; Shared: outline generation via LLM
;; =============================================================================

(def ^:private outline-generation-tools
  "Forces the LLM to emit a structured outline as a JSON tool call."
  [{:type "function"
    :function
    {:name "emitOutline"
     :description "Emit the structured documentation outline."
     :parameters
     {:type "object"
      :required ["title" "sections"]
      :properties
      {:title {:type "string" :description "Suggested page title."}
       :sections
       {:type "array"
        :description "Top-level sections in the proposed outline (4-7 typical)."
        :items
        {:type "object"
         :required ["heading" "purpose"]
         :properties
         {:heading {:type "string" :description "Short section heading."}
          :purpose {:type "string" :description "1-2 sentences on what this section covers and why."}
          :prior_art_pages
          {:type "array"
           :items {:type "string"}
           :description "URLs or page slugs of existing docs that should be referenced. Empty if none."}}}}
       :consistency_notes
       {:type "array"
        :items {:type "string"}
        :description "Plain-text notes about overlaps, contradictions, or duplication risks observed in the prior art. Empty if none."}}}}}])

(defn- build-outline-prompt
  [topic retrieved-context audience]
  (str "You are drafting a documentation outline for an Altinn 3 doc page on "
       "https://docs.altinn.studio.\n\n"
       "Topic: " topic "\n"
       (when-not (str/blank? audience)
         (str "Intended audience: " audience "\n"))
       (when-not (str/blank? retrieved-context)
         (str "\nPrior-art context (existing docs the system retrieved):\n"
              retrieved-context "\n"))
       "\nPropose a structured outline by calling the emitOutline tool. "
       "Aim for 4-7 top-level sections. For each section, list any existing-doc "
       "page references that look directly relevant. If you noticed substantive "
       "overlaps or contradictions in the prior-art, surface them in consistency_notes."))

(defn- resolve-tenant
  [opts-or-ctx]
  (or (get-in opts-or-ctx [:opts :tenant])
      (:tenant opts-or-ctx)
      (get-in opts-or-ctx [:skill-params :tenant])))

(defn generate-outline
  "Shared LLM driver. Returns {:outline parsed-map :model-used model} on success,
   or {:error message} on failure. Used by both the ReAct tool and the
   propose-outline skill."
  [{:keys [topic retrieved-context audience tenant temperature]
    :or {temperature 0.2}}]
  (cond
    (str/blank? topic)
    {:error "topic is required"}

    (str/blank? tenant)
    {:error "tenant is required for LLM credential lookup"}

    :else
    (let [model (if (llm/use-azure-openai tenant)
                  (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
                  (cfg/get {:tenant tenant} :services :azure-openai :model-name))
          prompt (build-outline-prompt topic retrieved-context audience)
          request {:model model
                   :messages [{:role "user" :content prompt}]
                   :tools outline-generation-tools
                   :tool_choice {:type "function" :function {:name "emitOutline"}}
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
                       (timbre/warn t "propose-outline LLM call failed")
                       {:error (.getMessage t)}))]
      (if (:error response)
        response
        (let [json-args (some-> response :choices first :message :tool_calls first :function :arguments)]
          (if json-args
            {:outline (json/read-str json-args :key-fn keyword)
             :raw-json json-args
             :model-used model}
            {:error (str "Model returned no emitOutline call. Raw content: "
                         (-> response :choices first :message :content))}))))))

(defn- render-outline-markdown
  "Pretty-print a parsed outline map as markdown for direct display in the chat."
  [{:keys [title sections consistency_notes]}]
  (let [section-md (fn [{:keys [heading purpose prior_art_pages]}]
                     (str "### " heading "\n"
                          (when-not (str/blank? purpose) (str purpose "\n"))
                          (when (seq prior_art_pages)
                            (str "*Prior art:* "
                                 (str/join ", " prior_art_pages)
                                 "\n"))))]
    (str "# " (or title "(untitled)") "\n\n"
         (when (seq sections)
           (str/join "\n" (map section-md sections)))
         (when (seq consistency_notes)
           (str "\n## Consistency notes\n\n"
                (str/join "\n" (map #(str "- " %) consistency_notes)))))))

;; =============================================================================
;; ReAct tool flavor (used by demo/altinn-authoring)
;; =============================================================================

(def propose-outline-tool-spec
  {:type "function"
   :function
   {:name "propose_outline"
    :description (str "Generate a structured documentation outline for a given topic. "
                      "Returns a typed outline (title + sections + consistency notes). "
                      "Call this once you have gathered enough prior-art context.")
    :parameters
    {:type "object"
     :required ["topic"]
     :properties
     {:topic {:type "string"
              :description "The topic/feature the new doc page should cover."}
      :retrieved_context {:type "string"
                          :description "Optional summary of prior research from previous tool calls."}
      :audience {:type "string"
                 :description "Optional intended reader."}}}}})

(defn execute-propose-outline-tool
  "Tool execute fn for the ReAct loop. Returns a JSON string the LLM consumes."
  [args _!workspace ambient-ctx]
  (let [topic (or (:topic args) (get args "topic"))
        retrieved-context (or (:retrieved_context args) (get args "retrieved_context") "")
        audience (or (:audience args) (get args "audience") "")
        tenant (resolve-tenant ambient-ctx)
        result (generate-outline {:topic topic
                                  :retrieved-context retrieved-context
                                  :audience audience
                                  :tenant tenant})]
    (if (:error result)
      (str "Error generating outline: " (:error result))
      (str "Outline proposal:\n" (:raw-json result)))))

;; =============================================================================
;; Skill flavor (used inside docs/outline-graph)
;; =============================================================================

(defn- format-context-docs
  "Render reranked context docs as numbered passages for the outline prompt.
   Mirrors digdir.skills.builtin.synthesis/build-context-yaml — rerank emits
   docs as {:page_content \"...\" :metadata {:source chunk-id}}, where
   :page_content already includes the title and header metadata as a header
   block followed by the chunk markdown body."
  [context-docs]
  (->> context-docs
       (take 12)
       (map-indexed (fn [i doc]
                      (str "[" (inc i) "] "
                           (some-> doc :metadata :source (->> (str "source=") (str "  ")))
                           "\n"
                           (:page_content doc))))
       (str/join "\n\n---\n\n")))

(def propose-outline-skill-metadata
  {:skill-id :docs/propose-outline
   :name "Propose Outline (demo)"
   :description "Synthesis-stage replacement that emits a structured outline instead of a freeform answer. Used by the docs/outline-graph skill graph."
   :category :generation
   :inputs [:query :context-docs]
   :outputs [:response :outline]
   :parameters {:model :string
                :temperature :number
                :audience :string}
   ;; Honest declaration — credentials are resolved via cfg/get at use site;
   ;; `check-required-services` exempts the use-site-resolved-services set
   ;; (:azure-openai, :colbert) from its presence check.
   :required-services #{:azure-openai}
   :version "1.0.0"
   :tags #{:demo :authoring :synthesis}})

(defn execute-propose-outline-skill
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [query context-docs]} inputs
        {:keys [temperature audience]} (or parameters {})
        tenant (or (:tenant skill-params) "digdir")
        retrieved-context (format-context-docs context-docs)
        result (generate-outline {:topic query
                                  :retrieved-context retrieved-context
                                  :audience (or audience "")
                                  :tenant tenant
                                  :temperature (or temperature 0.2)})]
    (if (:error result)
      (skills/error-result :docs/propose-outline-failed
                           (:error result)
                           {:stage :docs/propose-outline})
      (skills/success-result
       {:response (render-outline-markdown (:outline result))
        :outline (:outline result)}
       {:model-used (:model-used result)
        :context-doc-count (count context-docs)
        :section-count (count (get-in result [:outline :sections]))}))))

(def propose-outline-skill
  {:metadata propose-outline-skill-metadata
   :execute execute-propose-outline-skill})

;; =============================================================================
;; Custom skill graph (used by demo/altinn-outline-graph)
;; =============================================================================

(def outline-graph
  "plan → retrieve → rerank → propose-outline.
   Retrieval-then-structured-synthesis pipeline; replaces the freeform
   synthesis step of a typical RAG graph with the propose-outline skill
   so every run emits a structured outline instead of prose."
  {:id :docs/outline-graph
   :name "Demo Outline Graph"
   :description "Authoring-focused graph: retrieval + rerank, then a structured outline synthesizer."
   :inputs [:user-query :docs-collection :chunks-collection :phrases-collection :conversation-history]
   :outputs [:response :outline :chunks :search-phrases]
   :steps [{:id :plan
            :skill :builtin/query-planner
            :inputs {:query :$user-query
                     :conversation-history :$conversation-history}}
           {:id :retrieve
            :skill :builtin/retrieval
            :inputs {:queries [:plan :queries]
                     :docs-collection :$docs-collection
                     :chunks-collection :$chunks-collection
                     :phrases-collection :$phrases-collection}}
           {:id :rerank
            :skill :builtin/rerank
            :inputs {:chunks [:retrieve :chunks]
                     :query :$user-query
                     :docs-collection :$docs-collection}}
           {:id :synthesize
            :skill :docs/propose-outline
            :inputs {:query :$user-query
                     :context-docs [:rerank :context-docs]}}]})

(def outline-skill-graph
  (templates/make-skill-graph
   :docs/outline-graph
   "Demo Outline Graph"
   "Custom synthesis step that emits a structured Altinn doc outline."
   outline-graph
   {:version "1.0.0"
    :tags #{:demo :authoring}
    :input-schema templates/agent-tool-input-schema}))

;; =============================================================================
;; Agent definitions
;; =============================================================================

;; =============================================================================
;; Registration
;; =============================================================================
;; Per-demo agents removed in Phase 0 — :builtin/docs-agent (agents/core.clj)
;; now owns docs/outline-graph along with the other docs/* skill graphs.

(defn register!
  "Register the demo's tool, skill, and skill graph. Idempotent."
  []
  (agent-tools/register-tool! "propose_outline"
                              propose-outline-tool-spec
                              execute-propose-outline-tool)
  (skills/register-skill! propose-outline-skill)
  (templates/register-skill-graph! outline-skill-graph))
