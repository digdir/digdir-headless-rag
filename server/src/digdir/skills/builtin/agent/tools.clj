(ns digdir.skills.builtin.agent.tools
  "Agent tool definitions and execution bridge."
  (:require [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.llm.client :as llm-client]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.context :as ctx]
            [digdir.rag.core :as rag]
            [digdir.rag.filters :as filters]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts-client]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;; =============================================================================
;; Agent Tool Definitions (exposed to the LLM inside the loop)
;; =============================================================================

(def agent-tools
  "Tool definitions the LLM can call during the agentic loop.
   These are simplified interfaces — the agent manages collection
   context and workspace state internally."
  [{:type "function"
    :function
    {:name "search"
     :description "Search the document collection using multiple queries. Returns metadata-only results (chunk_id, doc_num, chunk_index, total_chunks, content_length, headers, scores) — NO content. Use read_chunks to fetch content of promising results."
     :parameters
     {:type "object"
      :properties
      {:queries
       {:type "array"
        :items {:type "string"}
        :description "Search queries to execute against the document collection"}
       :filter_by
       {:type "object"
        :description "Optional structured retrieval filter to constrain document search."
        :properties
        {:fields
         {:type "array"
          :items
          {:type "object"
           :properties
           {:field {:type "string"
                    :description "Document field to filter on (for example: owner_short, orgs_short, year, doc_num, title)"}
            :selected_options {:type "array"
                               :items {:type "string"}
                               :description "Exact values to include"}
            :value_type {:type "string"
                         :enum ["string" "integer"]
                         :description "Filter value type. Defaults to string."}}
           :required ["field" "selected_options"]}}}}}
      :required ["queries"]}}}

   {:type "function"
    :function
    {:name "read_chunks"
     :description "Read full content of specific chunks. Use after search to read promising results. Supports reading by chunk IDs or by document + chunk index range."
     :parameters
     {:type "object"
      :properties
      {:chunk_ids {:type "array" :items {:type "string"} :description "Specific chunk IDs to read"}
       :doc_num {:type "string" :description "Document number for range-based reading"}
       :chunk_range {:type "object"
                     :properties {:from {:type "integer"} :to {:type "integer"}}
                     :description "Chunk index range within a document (requires doc_num)"}
       :max_content_length {:type "integer" :description "Truncate each chunk's content to this many characters"}}}}}

   {:type "function"
    :function
    {:name "plan_queries"
     :description "Expand a question into multiple diverse search queries for better coverage. Use this before search_documents when the question is complex or multi-faceted."
     :parameters
     {:type "object"
      :properties
      {:query
       {:type "string"
        :description "The question to expand into search queries"}}
      :required ["query"]}}}

   {:type "function"
    :function
    {:name "rerank_results"
     :description "Rerank the accumulated workspace chunks by relevance to a query. Use this after search_documents to get the most relevant results."
     :parameters
     {:type "object"
      :properties
      {:query
       {:type "string"
        :description "The query to rank relevance against"}}
      :required ["query"]}}}

   {:type "function"
    :function
    {:name "generate_response"
     :description "Generate a final response using the top-ranked context from the workspace. Use this after you have gathered and optionally reranked enough relevant context."
     :parameters
     {:type "object"
      :properties
      {:query
       {:type "string"
        :description "The question to answer using the gathered context"}}
      :required ["query"]}}}
   {:type "function"
    :function
    {:name "inspect_filters"
     :description "Inspect available filter fields for the docs collection, and optionally fetch top facet values for specific fields."
     :parameters
     {:type "object"
      :properties
      {:fields
       {:type "array"
        :items {:type "string"}
        :description "Optional field names to inspect facet values for"}
       :max_options
       {:type "integer"
        :description "Maximum facet options per field (default 10)"}}}}}])

(def ^:private dataset-bound-base-tools
  #{"search" "read_chunks" "inspect_filters"})

(def ^:private generic-pipeline-alias-tokens
  #{"assistant" "default" "main" "pipeline" "rag" "dataset"
    "dev" "prod" "test" "qa" "stage" "staging" "preview"})

(defn- slugify-tool-token
  [value]
  (let [token (some-> value
                      str/lower-case
                      (str/replace #"[^a-z0-9]+" "_")
                      (str/replace #"^_+|_+$" "")
                      (str/replace #"_+" "_"))]
    (cond
      (str/blank? token) "dataset"
      (re-matches #"^[0-9].*" token) (str "dataset_" token)
      :else token)))

(defn- distinct-dataset-scopes
  [dataset-scopes]
  (reduce (fn [acc dataset-scope]
            (if (some #(= dataset-scope %) acc)
              acc
              (conj acc dataset-scope)))
          []
          dataset-scopes))

(defn- dataset-alias-key
  [dataset-ref]
  [(:tenant dataset-ref)
   (:dataset-config-key dataset-ref)])

(defn- dataset-alias-label
  [dataset-ref dataset-refs]
  (let [tenant-token (slugify-tool-token (:tenant dataset-ref))
        config-key-token (slugify-tool-token (:dataset-config-key dataset-ref))
        pipeline-token config-key-token
        pipeline-counts (frequencies (map (comp slugify-tool-token
                                                :dataset-config-key)
                                          dataset-refs))
        tenant-counts (frequencies (map (comp slugify-tool-token :tenant) dataset-refs))
        tenant+pipeline-counts (frequencies (map (fn [ref]
                                                   [(slugify-tool-token (:tenant ref))
                                                    (slugify-tool-token (:dataset-config-key ref))])
                                                 dataset-refs))
        generic-pipeline? (contains? generic-pipeline-alias-tokens pipeline-token)]
    (cond
      (and (not generic-pipeline?)
           (= 1 (get pipeline-counts pipeline-token 0)))
      pipeline-token

      (= 1 (get tenant-counts tenant-token 0))
      tenant-token

      (= 1 (get tenant+pipeline-counts [tenant-token pipeline-token] 0))
      (str tenant-token "_" pipeline-token)

      :else
      (str tenant-token "_" config-key-token "_" pipeline-token))))

(defn- alias-tool-name
  [base-tool-name dataset-label]
  (case base-tool-name
    "search" (str "query_" dataset-label)
    "read_chunks" (str "read_" dataset-label)
    "inspect_filters" (str "inspect_" dataset-label "_filters")
    base-tool-name))

(defn- alias-tool-description
  [base-tool-name dataset-ref description]
  (let [dataset-path (str (:tenant dataset-ref) "/" (:dataset-config-key dataset-ref))
        bound-prefix (case base-tool-name
                       "search" "Query only the bound dataset."
                       "read_chunks" "Read chunk content only from the bound dataset."
                       "inspect_filters" "Inspect filter metadata only for the bound dataset."
                       "Tool is bound to a specific dataset.")]
    (str bound-prefix " Dataset: " dataset-path ". " description)))

(defn- dataset-tool-binding
  [base-tool dataset-ref dataset-label]
  (let [base-tool-name (get-in base-tool [:function :name])
        alias-name (alias-tool-name base-tool-name dataset-label)]
    {:tool-name alias-name
     :base-tool-name base-tool-name
     :dataset-ref dataset-ref
     :tool-definition
     (-> base-tool
         (assoc-in [:function :name] alias-name)
         (assoc-in [:function :description]
                   (alias-tool-description base-tool-name
                                           dataset-ref
                                           (get-in base-tool [:function :description]))))}))

(defn- dataset-tool-bindings
  [ambient-ctx]
  (let [dataset-scopes (->> (or (:allowed-dataset-scopes ambient-ctx)
                                (some-> (:dataset-ref ambient-ctx) vector)
                                [])
                            (keep ctx/normalize-dataset-ref)
                            distinct-dataset-scopes
                            vec)
        bound-tools (filter #(contains? dataset-bound-base-tools
                                        (get-in % [:function :name]))
                            agent-tools)]
    (mapcat (fn [dataset-scope]
              (let [dataset-label (dataset-alias-label dataset-scope dataset-scopes)]
                (map #(dataset-tool-binding % dataset-scope dataset-label) bound-tools)))
            dataset-scopes)))

;; =============================================================================
;; External Tool Registry
;; =============================================================================
;;
;; Code outside this module can register additional tools the ReAct loop will
;; expose to the LLM. Used by user-defined agents that need a primitive the
;; built-in toolkit doesn't provide (customization flavor 3 in the Altinn-doc
;; demo plan). Registered tools are appended to the toolset returned by
;; `agent-tool-definitions` and dispatched ahead of the built-in `cond` in
;; `execute-tool-call*`. They do not participate in dataset-binding aliasing —
;; they receive `ambient-ctx` and may consult `:opts`/`:dataset-ref` themselves.

(defonce ^:private !registered-tools
  (atom {}))

(defn register-tool!
  "Register an external tool with the agent ReAct loop.

   Args:
     tool-name    String matching openai-spec's :function/:name.
     openai-spec  Full {:type \"function\" :function {:name :description :parameters ...}}
                  OpenAI tool definition.
     execute-fn   (fn [args !workspace ambient-ctx] -> result-text)
                  Returns a string the LLM will see as the tool result. Throw
                  or return an error string on failure.

   Re-registering the same tool-name overwrites the prior entry."
  [tool-name openai-spec execute-fn]
  (assert (string? tool-name) "tool-name must be a string")
  (assert (= tool-name (get-in openai-spec [:function :name]))
          "tool-name must match openai-spec :function/:name")
  (assert (fn? execute-fn) "execute-fn must be a function")
  (swap! !registered-tools assoc tool-name
         {:definition openai-spec
          :execute-fn execute-fn})
  tool-name)

(defn registered-tool-definitions
  "OpenAI tool specs for every externally-registered tool, in registration order."
  []
  (mapv :definition (vals @!registered-tools)))

(defn registered-tool-names
  []
  (set (keys @!registered-tools)))

(defn agent-tool-definitions
  [ambient-ctx]
  (let [bindings (vec (dataset-tool-bindings ambient-ctx))
        generic-dataset-tools (filter #(contains? dataset-bound-base-tools
                                                  (get-in % [:function :name]))
                                      agent-tools)
        generic-shared-tools (remove #(contains? dataset-bound-base-tools
                                                 (get-in % [:function :name]))
                                     agent-tools)
        registered (registered-tool-definitions)]
    (cond
      (empty? bindings)
      (vec (concat agent-tools registered))

      (= 1 (count (distinct (map dataset-alias-key (map :dataset-ref bindings)))))
      (vec (concat generic-dataset-tools
                   generic-shared-tools
                   (map :tool-definition bindings)
                   registered))

      :else
      (vec (concat (map :tool-definition bindings)
                   generic-shared-tools
                   registered)))))

(defn- bound-ambient-ctx
  [ambient-ctx dataset-ref]
  (let [{:keys [tenant dataset-config-key dataset-inputs]}
        (ctx/resolve-dataset-context dataset-ref)
        opts (-> (or (:opts ambient-ctx) {})
                 (assoc :tenant tenant
                        :dataset-config-key dataset-config-key
                        :dataset-ref dataset-ref)
                 (update :skill-params
                         (fnil merge {})
                         {:tenant tenant
                          :dataset-config-key dataset-config-key
                          :dataset-ref dataset-ref}))]
    (-> ambient-ctx
        (merge dataset-inputs)
        (assoc :dataset-ref dataset-ref
               :opts opts))))

(defn resolve-tool-binding
  [tool-name ambient-ctx]
  (if-let [binding (some #(when (= tool-name (:tool-name %)) %) (dataset-tool-bindings ambient-ctx))]
    {:requested-tool-name tool-name
     :base-tool-name (:base-tool-name binding)
     :dataset-ref (:dataset-ref binding)
     :ambient-ctx (bound-ambient-ctx ambient-ctx (:dataset-ref binding))}
    {:requested-tool-name tool-name
     :base-tool-name tool-name
     :dataset-ref (:dataset-ref ambient-ctx)
     :ambient-ctx ambient-ctx}))

(defn available-tool-names
  [ambient-ctx]
  (mapv #(get-in % [:function :name]) (agent-tool-definitions ambient-ctx)))

;; =============================================================================
;; Tool Result Formatting
;; =============================================================================

(defn format-search-result
  "Format search result as concise text for the LLM."
  [result new-count total-count]
  (if (:error result)
    (str "Error executing search_documents: "
         (get-in result [:error :error-message]))
    (let [attribution (get-in result [:outputs :search-attribution])
          auto-filter (:auto-filter-applied attribution)
          fallback (:auto-filter-fallback attribution)
          filter-field (get-in auto-filter [:fields 0 :field])
          filter-orgs (get-in auto-filter [:fields 0 :selected-options])]
      (str "Retrieved " (count (get-in result [:outputs :chunks])) " chunks"
           " (" new-count " new unique)."
           " Workspace now has " total-count " total chunks."
           (when attribution
             (str " [" (:phrase attribution) " phrase, "
                  (:metadata attribution) " metadata, "
                  (:content attribution) " content]"))
           (when auto-filter
             (str " Auto-filtered by " filter-field ": "
                  (str/join ", " filter-orgs) "."))
           (when fallback
             " (filter returned 0 results, fell back to unfiltered)")))))

(defn format-plan-result
  "Format query planner result as concise text for the LLM."
  ([result]
   (format-plan-result result (or (get-in result [:outputs :queries])
                                  (get-in result [:outputs :search-phrases]))))
  ([result phrases]
   (if (:error result)
     (str "Error executing plan_queries: "
          (get-in result [:error :error-message]))
     (str "Generated " (count phrases) " queries: "
          (str/join ", " (map #(str "\"" % "\"") phrases))))))

(defn format-rerank-result
  "Format rerank result as concise text for the LLM."
  [result]
  (if (:error result)
    (str "Error executing rerank_results: "
         (get-in result [:error :error-message]))
    (let [reranked (or (get-in result [:outputs :chunks])
                       (get-in result [:outputs :reranked-chunks]))
          top-chunks (take 3 reranked)]
      (if (empty? reranked)
        "No chunks in workspace to rerank. Use read_chunks first to load content."
        (str "Reranked to top " (count reranked) " chunks."
             (when (seq top-chunks)
               (str " Top results: "
                    (str/join "; "
                      (map-indexed
                        (fn [i chunk]
                          (str (inc i) ". "
                               (subs (or (:content_markdown chunk) "") 0
                                     (min 80 (count (or (:content_markdown chunk) ""))))))
                        top-chunks)))))))))

(defn format-generate-result
  "Format synthesis result using a workspace-aware sufficiency decision."
  ([result]
   (if (:error result)
     (str "Error executing generate_response: "
          (get-in result [:error :error-message]))
     (get-in result [:outputs :response] "")))
  ([result decision]
   (if (:error result)
     (str "Error executing generate_response: "
          (get-in result [:error :error-message]))
     (or (:message decision)
         (get-in result [:outputs :response] "")))))

(defn- infer-value-type
  "Map Typesense field types to filter value type hints."
  [typesense-type]
  (let [t (str/lower-case (str typesense-type))]
    (if (str/starts-with? t "int")
      :integer
      :string)))

(defn- collection-schema->filter-fields
  "Extract available facetable fields from a Typesense collection schema."
  [collection-schema]
  (->> (:fields collection-schema)
       (filter :facet)
       (map (fn [{:keys [name type facet]}]
              {:field name
               :value-type (infer-value-type type)
               :typesense-type type
               :facet facet}))
       (sort-by :field)
       vec))

(defn format-filter-inspection-result
  "Format inspect_filters results for the agent."
  [{:keys [available-fields facet-options]}]
  (let [field-lines (->> available-fields
                         (map (fn [{:keys [field value-type typesense-type]}]
                                (str "- " field " (type: " typesense-type ", filter-value-type: " (name value-type) ")")))
                         (str/join "\n"))
        facet-lines (when (seq facet-options)
                      (str/join
                        "\n"
                        (map (fn [[field options]]
                               (str "- " field ": "
                                    (if (seq options)
                                      (str/join ", " (map #(str "\"" (:name %) "\"(" (:count %) ")") options))
                                      "(no options found)")))
                             facet-options)))]
    (str "Available filter fields:\n"
         (if (str/blank? field-lines) "(none)" field-lines)
         (when facet-lines
           (str "\n\nTop facet options:\n" facet-lines)))))

(def ^:private default-search-result-display-limit
  "Max number of ranked chunks surfaced in the search tool result text.
   Chunks are already sorted by retrieval prior, so the top-N carries the
   signal the agent needs to pick read_chunks targets. Lower-ranked chunks
   remain in workspace state and can still be read by chunk_id if the LLM
   happens to know them, but they are omitted from the prompt to keep the
   iteration-over-iteration cached prefix small."
  20)

(defn- query-tokens
  "Tokenise a query into lower-cased word tokens >= 3 chars long.
   Used by the title-bonus rescoring to find query-vs-doc-metadata overlap."
  [query]
  (when (string? query)
    (->> (str/split (str/lower-case query) #"\W+")
         (remove #(< (count %) 3))
         set)))

(defn- title-overlap-bonus
  "Count of query tokens present in a chunk's title, headers, and url.
   Acts as a cheap lexical surrogate for \"does this doc look like it's
   about the question?\" — distinct from ColBERT's content-density scoring,
   which doesn't always promote on-topic title matches above generic
   content-dense neighbours. See Option H from the F4 follow-up
   investigation (May 2026): trace of `altinn-studio-create-user` showed
   ColBERT placing the matching `/nb/altinn-studio/v8/getting-started/create-user/`
   doc behind less-relevant chunks because of content density."
  [q-tokens chunk]
  (if-not (seq q-tokens)
    0
    (let [doc-ref (some (fn [v] (when (and (map? v)
                                           (or (contains? v :title)
                                               (contains? v :url)))
                                  v))
                        (vals chunk))
          title (or (:title doc-ref) "")
          url (or (:url doc-ref) "")
          headers-str (let [m (:metadata chunk)]
                        (cond
                          (string? m) m
                          (map? m) (str/join " " (concat (keys m) (vals m)))
                          :else ""))
          haystack-tokens (->> (str/lower-case (str title " " url " " headers-str))
                               (re-seq #"\w+")
                               set)]
      (count (filter haystack-tokens q-tokens)))))

(defn- combined-search-score
  "Hybrid sort key: rerank (when present) + title-overlap × weight. The
   chunk vector returned by the retrieval skill is already sorted by ColBERT;
   we re-sort by this combined score so doc-title-matching candidates
   surface above content-dense-but-off-topic ones."
  [q-tokens weight chunk]
  (let [rerank (some-> (:rerank-score chunk) double)
        prior (some-> (:retrieval-prior chunk) double)
        base (or rerank prior 0.0)
        bonus (* (double weight) (title-overlap-bonus q-tokens chunk))]
    (+ base bonus)))

(defn rescore-with-title-bonus
  "Re-sort chunks by hybrid (rerank + title-overlap-bonus) for a given
   query. Returns chunks in the new order with `:title-overlap-bonus`
   added to each chunk so the renderer can surface the signal to the LLM.
   Opt-in via `{:builtin/agent {:title-overlap-weight W}}` skill-params;
   W=0 disables (returns chunks unchanged)."
  [chunks query weight]
  (if (or (not (pos? weight)) (empty? chunks) (str/blank? (str query)))
    chunks
    (let [q-tokens (query-tokens query)]
      (->> chunks
           (map (fn [c] (assoc c :title-overlap-bonus
                               (title-overlap-bonus q-tokens c))))
           (sort-by #(combined-search-score q-tokens weight %) >)
           vec))))

(defn- agent-strategy-quota
  "Read the `:search-strategy-quota` parameter from the agent's skill-params.
   When > 0, the search handler reorders `:reranked-chunks` so the top of
   the visible list interleaves the top-K chunks from each base strategy
   (phrase, metadata, content). 0 (default) preserves the rerank order.

   Rationale (Round-8 post-mortem): the default merge + ColBERT rerank
   produces a top-20 dominated by chunks that score well across multiple
   strategies, displacing single-strategy hits — especially phrase-only
   matches. Per-strategy quota gives those single-strategy hits guaranteed
   representation in the agent's visible search-result window."
  [ambient-ctx]
  (let [raw (get-in ambient-ctx [:opts :skill-params :builtin/agent :search-strategy-quota])
        n (cond
            (nil? raw) 0
            (number? raw) (long raw)
            :else 0)]
    (max 0 (min 10 n))))

(defn- enforce-strategy-quota
  "Reorder `chunks` so the head of the list contains, in round-robin
   interleave, the top-`quota` chunks from each of phrase / metadata /
   content strategies (per `:search-types`). The remaining chunks follow
   in their original rerank order.

   A chunk may belong to multiple strategies (it shows up in multiple
   `:search-types`), in which case it counts for each. Duplicates across
   strategies are deduped by `:chunk_id` keeping the first occurrence.

   `quota`=0 returns chunks unchanged. `quota`=3 puts the top-3 phrase,
   top-3 metadata, top-3 content (≤9 unique chunks) at positions 1-N
   before the rest of the rerank-sorted list."
  [chunks quota]
  (if (or (not (pos? quota)) (empty? chunks))
    chunks
    (let [chunks (vec chunks)
          strategies [:phrase :metadata :content]
          per-strategy (into {}
                             (for [s strategies]
                               [s (->> chunks
                                       (filter (fn [c]
                                                 (let [t (or (:search-types c) #{})
                                                       t (if (set? t) t (into #{} t))]
                                                   (contains? t s))))
                                       (take quota)
                                       vec)]))
          interleaved (apply concat
                             (for [i (range quota)]
                               (keep (fn [s] (get-in per-strategy [s i])) strategies)))
          ;; Dedupe by chunk_id, keep first occurrence
          {:keys [seen ordered]}
          (reduce (fn [{:keys [seen ordered]} c]
                    (if (contains? seen (:chunk_id c))
                      {:seen seen :ordered ordered}
                      {:seen (conj seen (:chunk_id c))
                       :ordered (conj ordered c)}))
                  {:seen #{} :ordered []}
                  interleaved)
          rest-chunks (remove #(contains? seen (:chunk_id %)) chunks)]
      (vec (concat ordered rest-chunks)))))

(defn- agent-search-display-limit
  "Read the `:search-display-limit` parameter from the agent's skill-params.
   Controls how many ranked chunks the `search_documents` tool result
   surfaces to the LLM in the prompt. Default is the production
   `default-search-result-display-limit` (20). Capped at 100 to bound the
   prompt-token cost of a single search result; chunks beyond that stay
   in workspace state and remain reachable by chunk_id, but don't show up
   in the immediate tool-result rendering.

   Diagnostic context (Round-8 post-mortem): the widenet retrieval config
   often places goldens at merged ranks 21-50 of the returned candidate
   list. The default 20-line display hides those from the LLM, which is
   then constrained to read from chunks 1-20 (where goldens often aren't
   present under default merge weights)."
  [ambient-ctx]
  (let [raw (get-in ambient-ctx [:opts :skill-params :builtin/agent :search-display-limit])
        n (cond
            (nil? raw) default-search-result-display-limit
            (number? raw) (long raw)
            :else default-search-result-display-limit)]
    (max 1 (min 100 n))))

(defn- agent-title-overlap-weight
  "Read the Option H `:title-overlap-weight` parameter from the agent's
   skill-params. Returns 0 (off) when absent. When > 0 the search handler
   re-scores chunks by (rerank + W × title-overlap) before showing them
   to the LLM, surfacing on-topic title/url matches above content-density.

   Choosing W: ColBERT rerank scores in observed traces sit roughly in the
   18-28 range across the top-20, so at W=5 a single token match is worth
   about half a rerank slot and a 3-token match becomes dominant. That was
   the value tuned to give title-relevance roughly equal voice to
   content-density. (Carried over from a `default-title-overlap-weight`
   constant that was never wired to anything — the effective default is
   and has been 0.)"
  [ambient-ctx]
  (let [raw (get-in ambient-ctx [:opts :skill-params :builtin/agent :title-overlap-weight])
        w (cond
            (nil? raw) 0.0
            (number? raw) (double raw)
            :else 0.0)]
    (max 0.0 (min 20.0 w))))

(defn- agent-auto-read-top-k
  "Read the F4 `:auto-read-top-k` parameter from the agent's skill-params.
   Returns 0 (disabled) when absent, capped to a sensible upper bound to
   keep the budget impact bounded. F4 is the agent-read-selection failure
   mode identified in the Arc A diagnostic — at K>0 the search tool auto-
   reads the top-K chunks by merged rank so they reach the workspace
   regardless of what the LLM picks to read."
  [ambient-ctx]
  (let [raw (get-in ambient-ctx [:opts :skill-params :builtin/agent :auto-read-top-k])
        k (cond
            (nil? raw) 0
            (number? raw) (long raw)
            :else 0)]
    (max 0 (min 10 k))))

(defn- agent-show-snippets?
  "Whether to render the per-chunk read-decision `snippet=` preview in search
   results. OFF by default (the metadata-only display is unchanged); enable via
   `:skill-params {:builtin/agent {:search-snippets true}}`. The snippet itself
   is always attached upstream at rerank time — this only gates DISPLAY, so the
   A/B is a clean render-only toggle."
  [ambient-ctx]
  (boolean (get-in ambient-ctx [:opts :skill-params :builtin/agent :search-snippets])))

(declare execute-sub-skill)

(defn- auto-read-top-k!
  "Issue a read_chunks call for the top-K chunks (by current ordering) of
   `chunks`, respecting the read budget, and add the resulting full-content
   chunks to the workspace. Returns the chunk-ids that were successfully
   auto-read (possibly empty when K=0, when the read budget is exhausted,
   or when every candidate was already in workspace).

   Errors are swallowed: F4 is an additive optimisation; a backend hiccup
   here should not interrupt the agent loop, only fail to provide the
   supplementary pre-reads."
  [!workspace ambient-ctx chunks k]
  (try
    (let [{:keys [docs-collection chunks-collection opts]} ambient-ctx
          budget (workspace/budget-state @!workspace)]
      (if (or (not (pos? k))
              (:read-operations-exhausted? budget)
              (:read-content-budget-exhausted? budget))
        []
        (let [already-read-ids (->> (:read-history @!workspace)
                                    (mapcat (fn [r] (or (:returned-chunk-ids r)
                                                        (:chunk-ids r))))
                                    set)
              candidate-ids (->> chunks
                                 (map :chunk_id)
                                 (remove nil?)
                                 (remove already-read-ids)
                                 (take k)
                                 vec)]
          (if (empty? candidate-ids)
            []
            (let [read-result (execute-sub-skill
                               :builtin/read-chunks
                               {:chunk-ids candidate-ids
                                :docs-collection docs-collection
                                :chunks-collection chunks-collection}
                               opts
                               nil)
                  raw-chunks (get-in read-result [:outputs :chunks] [])
                  ;; Trim to remaining read-content budget so a flood of
                  ;; large chunks doesn't immediately exhaust the LLM-side
                  ;; budget that the agent's own read decisions still need.
                  remaining-budget (:read-content-length-remaining
                                    (workspace/budget-state @!workspace))
                  {:keys [kept-chunks]} (workspace/trim-chunks-to-budget raw-chunks remaining-budget)]
              (when (seq kept-chunks)
                (workspace/record-read! !workspace
                                        {:chunk-ids (mapv :chunk_id kept-chunks)
                                         :auto-read? true}
                                        kept-chunks)
                (workspace/add-chunks-to-workspace! !workspace kept-chunks)
                (workspace/queue-pending-eval-chunks! !workspace kept-chunks))
              (mapv :chunk_id kept-chunks))))))
    (catch Throwable _
      [])))

(defn format-search-metadata-results
  "Format metadata-only search results for the LLM. No content — just IDs,
   titles, headers, content length, score, and rank — capped at
   `display-limit` rows (default 20).

   Kept deliberately terse: each line is ~90 chars instead of ~250. Dropped
   fields that the LLM does not use for chunk selection (hit-count, via).
   `retrieval-prior` is kept and rounded to three decimals; `original-rank`
   is rendered as `rank` for readability."
  ([chunks new-count total-seen search-pass attribution]
   (format-search-metadata-results chunks new-count total-seen search-pass attribution
                                   default-search-result-display-limit false))
  ([chunks new-count total-seen search-pass attribution display-limit]
   (format-search-metadata-results chunks new-count total-seen search-pass attribution
                                   display-limit false))
  ([chunks new-count total-seen search-pass attribution display-limit show-snippet?]
   (let [auto-filter (:auto-filter-applied attribution)
         fallback (:auto-filter-fallback attribution)
         rerank-enabled? (boolean (:rerank-enabled? attribution))
         rerank-ms (:rerank-ms attribution)
         rerank-error (:rerank-error attribution)
         filter-field (get-in auto-filter [:fields 0 :field])
         filter-orgs (get-in auto-filter [:fields 0 :selected-options])
         effective-limit (or display-limit default-search-result-display-limit)
         total-chunks (count chunks)
         shown-chunks (take effective-limit chunks)
         omitted-count (max 0 (- total-chunks (count shown-chunks)))
         chunk-lines (map-indexed
                       (fn [i chunk]
                         (let [doc-ref (some (fn [v] (when (and (map? v) (contains? v :title)) v)) (vals chunk))
                               metadata-str (let [m (:metadata chunk)]
                                              (cond
                                                (string? m) m
                                                (nil? m) ""
                                                :else (pr-str m)))
                               ;; Cap displayed matched-questions to the first two
                               ;; per chunk; the agent's read-decision needs the
                               ;; gist, not a flood. Each line is already ~90
                               ;; chars; one question adds ~60-80 more, two
                               ;; ~120-160. Two is enough to recognize the
                               ;; query/chunk fit; the remaining matches stay in
                               ;; the workspace summary for downstream evidence
                               ;; checks.
                               matched-qs (->> (or (:matched-questions chunk) [])
                                               (remove str/blank?)
                                               (take 2)
                                               vec)]
                           (str (inc i) ". chunk_id=" (:chunk_id chunk)
                                " doc_num=" (:doc_num chunk)
                                " chunk_index=" (:chunk_index chunk)
                                "/" (or (get doc-ref :total_chunks) "?")
                                (when-let [cl (:content_length chunk)]
                                  (str " content_length=" cl))
                                (when-let [title (:title doc-ref)]
                                  (str " title=\"" title "\""))
                                (when-let [url (:url doc-ref)]
                                  (str " url=" url))
                                (when (not (str/blank? metadata-str))
                                  (str " headers=" metadata-str))
                                (when-let [rr (:rerank-score chunk)]
                                  (str " rerank=" (format "%.2f" (double rr))))
                                (when-let [rp (:retrieval-prior chunk)]
                                  (str " score=" (format "%.3f" (double rp))))
                                (when-let [rank (:original-rank chunk)]
                                  (str " rank=" rank))
                                (when-let [tob (:title-overlap-bonus chunk)]
                                  (when (pos? tob)
                                    (str " title_match=" tob)))
                                (when (seq matched-qs)
                                  (str " matched_q="
                                       (str/join " | " (map pr-str matched-qs))))
                                ;; Read-decision preview: a bounded query-relevant
                                ;; window so the agent can self-select which chunk
                                ;; to read in full (gated; off = metadata-only).
                                (when-let [snip (and show-snippet? (:snippet chunk))]
                                  (when-not (str/blank? snip)
                                    (str " snippet=" (pr-str snip)))))))
                       shown-chunks)]
     (str "Search pass " search-pass ": found " total-chunks " chunks (" new-count " new). "
          total-seen " total seen."
          (when auto-filter
            (str " Auto-filtered by " filter-field ": "
                 (str/join ", " filter-orgs) "."))
          (when fallback
            " (filter returned 0 results, fell back to unfiltered)")
          (cond
            rerank-error
            (str " ColBERT rerank failed (" rerank-error ") — original ranking preserved.")

            rerank-enabled?
            (str " Ranking: ColBERT semantic rerank"
                 (when rerank-ms (str " (" rerank-ms "ms)"))
                 ". Higher rerank= means more semantically relevant to the query."))
          (when (pos? omitted-count)
            (str " Showing top " (count shown-chunks) " by rank; "
                 omitted-count " lower-ranked chunks omitted."
                 " Refine the query or read from the visible set."))
          "\n" (str/join "\n" chunk-lines)))))

(defn format-read-result
  "Format read results with full content for the LLM.

   Read signals are deliberately NOT surfaced in the tool-result text — they
   flow into trace files and diagnostics instead, so the LLM sees only content
   plus budget/skip notes here."
  [chunks max-content-length total-content-length cumulative-content-length budget-note skipped-note]
  (let [chunk-blocks
        (map (fn [chunk]
               (let [doc-ref (some (fn [v] (when (and (map? v) (contains? v :title)) v)) (vals chunk))
                     content (or (:content_markdown chunk) "")
                     display-content (if (and max-content-length (> (count content) max-content-length))
                                       (str (subs content 0 max-content-length) "\n[truncated]")
                                       content)]
                 (str "--- chunk_id=" (:chunk_id chunk)
                      " doc_num=" (:doc_num chunk)
                      " chunk_index=" (:chunk_index chunk)
                      (when-let [title (:title doc-ref)]
                        (str " title=\"" title "\""))
                      (when-let [url (:url doc-ref)]
                        (str " url=" url))
                      " ---\n"
                      display-content)))
             chunks)]
    (str "Read " (count chunks) " chunks"
         " (" total-content-length " chars this call, "
         cumulative-content-length " cumulative):\n\n"
         (str/join "\n\n" chunk-blocks)
         (when skipped-note
           (str "\n\n" skipped-note))
         (when budget-note
           (str "\n\n" budget-note)))))

(defn- skipped-non-supporting-read-note
  [skipped-chunk-ids]
  (when (seq skipped-chunk-ids)
    (str "Skipped "
         (count skipped-chunk-ids)
         " previously non-supporting chunk"
         (when (not= 1 (count skipped-chunk-ids)) "s")
         " for this query"
         (when (seq skipped-chunk-ids)
           (str ": " (pr-str (vec skipped-chunk-ids))))
         ". These chunks were already read and did not add support for the current question. "
         "Prefer unread chunks or start a more targeted search instead of rereading them.")))

(defn strip-page-markers
  "Remove {N} page-number markers from chunk content so the LLM
   does not confuse them with [N] citation indices."
  [text]
  (-> (or text "")
      (str/replace #"\{(\d+)\}" "")
      (str/replace #"\n-{3,}\n" "\n")))

;; =============================================================================
;; Execution Bridge
;; =============================================================================

(defn parse-tool-args
  "Parse JSON arguments from a tool call."
  [args-str]
  (try
    (json/read-str args-str :key-fn keyword)
    (catch Exception e
      {:error (str "Failed to parse tool arguments: " (.getMessage e))})))

(defn execute-sub-skill
  "Execute a sub-skill with proper context building.

   Args:
     skill-id - The skill to execute
     inputs - Input map for the skill
     opts - Map with :tenant, :dataset-config-key, :skill-params"
  ([skill-id inputs opts]
   (execute-sub-skill skill-id inputs opts nil))
  ([skill-id inputs opts parameters]
   (let [per-skill-params (get (:skill-params opts) skill-id)
         ;; Skill graph / config params provide defaults, while explicit
         ;; parameters passed by the agent tool call take precedence.
         merged-parameters (merge per-skill-params parameters)
         exec-ctx (ctx/build-execution-context
                    skill-id
                    inputs
                    (cond-> opts
                      (some? merged-parameters) (assoc :parameters merged-parameters)))]
     (skills/execute-skill skill-id exec-ctx))))

(defn- merged-sub-skill-parameters
  "Resolve effective sub-skill parameters from configured per-skill defaults
   and explicit overrides. Explicit overrides win."
  [skill-id opts explicit-parameters]
  (merge (get (:skill-params opts) skill-id) explicit-parameters))

(defn tool-stage-info
  [tool-name]
  (case tool-name
    ("search" "search_documents") {:stage :search
                                    :sub-skill :builtin/retrieval}
    "read_chunks" {:stage :read_chunks
                   :sub-skill :builtin/read-chunks}
    "plan_queries" {:stage :plan_queries
                    :sub-skill :builtin/query-planner}
    "inspect_filters" {:stage :inspect_filters
                       :sub-skill :builtin/inspect-filters}
    "rerank_results" {:stage :rerank_results
                      :sub-skill :builtin/rerank}
    "summarize" {:stage :summarize
                  :sub-skill :builtin/summarization}
    "generate_response" {:stage :generate_response
                         :sub-skill :builtin/synthesis}
    {:stage :unknown
     :sub-skill :unknown}))

;; llm-backed-stages moved to digdir.skills.builtin.agent.workspace (#25):
;; the claim is derived at the RECORDER, which every timing passes through,
;; rather than beside the value in the tool envelope. One list, not two.

(defn- usage-fields
  "Usage-side fields for a tool-call timing envelope (#25).

     :usage           summed across every completion made inside the call,
                      nil when none reported — so `no usage` stays
                      distinguishable from `zero tokens`
     :usage-writes    how many completions reported back. >1 on a single-call
                      stage is a DOUBLE-WRITE, and it is detectable here rather
                      than silently summed away
     :usage-expected? true when this stage is known to call an LLM, which is
                      what turns a missing :usage from an absence into an
                      INCONSISTENCY the artifact can carry"
  [stage captured]
  (cond-> (llm-client/usage-summary captured)
    ;; Belt and braces: the recorder derives this from the stage keyword too,
    ;; so the gate survives this key being dropped by an allowlist between here
    ;; and there — which is exactly what happened (#25).
    (contains? workspace/llm-backed-stages stage) (assoc :usage-expected? true)))

(defn normalize-filter-by
  "Normalize search_documents filter payload to the internal :filter-by shape."
  [filter-map]
  (when (map? filter-map)
    (let [fields (->> (:fields filter-map)
                      (keep (fn [field-spec]
                              (let [field (or (:field field-spec) (:name field-spec))
                                    field-type (or (:type field-spec) :multiselect)
                                    selected-options (or (:selected-options field-spec)
                                                         (:selected_options field-spec))
                                    value (or (:value field-spec)
                                              (:filter-value field-spec)
                                              (:filter_value field-spec)
                                              (:value_type field-spec))
                                    value-type (or (:value-type field-spec)
                                                   (:value_type field-spec)
                                                   :string)]
                                (when (and (string? field)
                                           (or (seq selected-options) (some? value)))
                                  (cond-> {:field field
                                           :type (keyword (name field-type))
                                           :value-type (keyword (name value-type))}
                                    (seq selected-options)
                                    (assoc :selected-options (set (map str selected-options)))
                                    (some? value)
                                    (assoc :value (str value)))))))
                      vec)]
      (when (seq fields)
        {:fields fields}))))

(defn- search-filters
  "What a search filters on: the caller's filter wins over the model's on each field,
   and a search that finds nothing falls back to the caller's filter alone."
  [args opts]
  (let [caller (get-in opts [:skill-params :builtin/retrieval :filter-by])
        model (normalize-filter-by (or (:filter-by args) (:filter_by args)))]
    {:model model
     :caller caller
     :filter-by (filters/merge-filter-maps caller model)}))

(defn execute-tool-call*
  "Execute a single tool call and return formatted result.

   Args:
     tool-name - Name of the tool to execute
     args - Parsed argument map
     !workspace - Workspace atom
     ambient-ctx - Map with :docs-collection, :chunks-collection,
                   :phrases-collection, :conversation-history, :opts"
  [tool-name args !workspace ambient-ctx]
  (let [{:keys [base-tool-name ambient-ctx]} (resolve-tool-binding tool-name ambient-ctx)
        tool-name base-tool-name]
    (if-let [{:keys [execute-fn]} (get @!registered-tools tool-name)]
      (execute-fn args !workspace ambient-ctx)
      (let [{:keys [docs-collection chunks-collection phrases-collection
                    conversation-history opts]} ambient-ctx
            workspace @!workspace
            budget (workspace/budget-state workspace)]
        (cond
          (#{"search" "search_documents"} tool-name)
      (if (:search-budget-exhausted? budget)
        (str "Search budget exhausted ("
             (:search-passes-used budget) "/" (:max-search-passes budget)
             "). Do not call search again; rerank or generate from current evidence.")
        (let [{model-filter :model caller-filter :caller filter-by :filter-by} (search-filters args opts)
              filter-errors (filters/filter-map-errors model-filter)
              ;; Slice 23: pass the planner's last user-intent into retrieval
              ;; when the agent did a plan_queries call earlier this turn. When
              ;; the retrieval skill has :user-intent-union-enabled, it will run
              ;; an intent-only second pass and union-merge.
              last-user-intent (:last-user-intent @!workspace)
              search-inputs (cond-> {:queries (:queries args)
                                     :docs-collection docs-collection
                                     :chunks-collection chunks-collection
                                     :phrases-collection phrases-collection}
                              last-user-intent (assoc :user-intent last-user-intent))
              filtered-result (when (empty? filter-errors)
                                (execute-sub-skill
                                 :builtin/retrieval
                                 search-inputs
                                 opts
                                 {:filter-by filter-by
                                  :metadata-only true
                                  :rerank-with-colbert true}))
              filtered-chunks (get-in filtered-result [:outputs :chunks] [])
              filtered-error (:error filtered-result)
              needs-unfiltered-fallback? (and filtered-result
                                              (nil? filtered-error)
                                              (not= (:fields filter-by) (:fields caller-filter))
                                              (empty? filtered-chunks))
              unfiltered-result (when needs-unfiltered-fallback?
                                  (execute-sub-skill
                                   :builtin/retrieval
                                   search-inputs
                                   opts
                                   {:filter-by caller-filter
                                    :metadata-only true
                                    :rerank-with-colbert true}))
              unfiltered-error (:error unfiltered-result)
              retry-label (if caller-filter
                            "retried with only the caller's required filter"
                            "retried without filters")]
          (cond
            (seq filter-errors)
            (str "Invalid filter_by, search not run: " (str/join " " filter-errors))

            filtered-error
            (do
              (workspace/record-search-error! !workspace {:queries (:queries args)
                                                          :filter-by filter-by
                                                          :error filtered-error
                                                          :fallback? false})
              (str "Search backend error: "
                   (:error-message filtered-error)))

            (and needs-unfiltered-fallback? unfiltered-error)
            (let [seen-ids (:seen-search-chunk-ids @!workspace)
                  new-chunks (remove #(contains? seen-ids (:chunk_id %)) filtered-chunks)
                  new-count (count new-chunks)
                  attribution (get-in filtered-result [:outputs :search-attribution])
                  _ (workspace/record-search! !workspace {:queries (:queries args)
                                                          :filter-by filter-by
                                                          :chunks filtered-chunks
                                                          :new-count new-count
                                                          :attribution attribution
                                                          :fallback? false})
                  _ (workspace/record-search-error! !workspace {:queries (:queries args)
                                                                :filter-by caller-filter
                                                                :error unfiltered-error
                                                                :fallback? true})
                  search-pass (count (:search-history @!workspace))
                  total-seen (count (:seen-search-chunk-ids @!workspace))
                  display-limit (agent-search-display-limit ambient-ctx)
                  base-summary (format-search-metadata-results filtered-chunks new-count total-seen search-pass attribution display-limit (agent-show-snippets? ambient-ctx))]
              (str base-summary
                   " Filtered retrieval returned 0 chunks; "
                   (if caller-filter
                     "retry with only the caller's required filter failed: "
                     "retry without filters failed: ")
                   (:error-message unfiltered-error)))

            :else
            (let [effective-result (or unfiltered-result filtered-result)
                  raw-chunks (get-in effective-result [:outputs :chunks] [])
                  ;; Option H: re-score chunks by (rerank + W × title-overlap)
                  ;; before showing them to the LLM, when the agent skill-params
                  ;; sets :title-overlap-weight > 0. Surfaces docs whose title/
                  ;; url tokens match the query above content-dense-but-off-
                  ;; topic neighbours.
                  title-w (agent-title-overlap-weight ambient-ctx)
                  query-for-bonus (or (first (:queries args)) "")
                  rescored-chunks (rescore-with-title-bonus raw-chunks
                                                            query-for-bonus
                                                            title-w)
                  ;; Filter 2 fix: per-strategy display quota — reorder
                  ;; so the head of the visible list interleaves the top-N
                  ;; chunks from phrase/metadata/content strategies.
                  ;; Compensates for single-strategy hits being demoted by
                  ;; multi-strategy winners in the merge + rerank.
                  strategy-quota (agent-strategy-quota ambient-ctx)
                  effective-chunks (enforce-strategy-quota rescored-chunks
                                                           strategy-quota)
                  seen-ids (:seen-search-chunk-ids @!workspace)
                  new-chunks (remove #(contains? seen-ids (:chunk_id %)) effective-chunks)
                  new-count (count new-chunks)
                  attribution (get-in effective-result [:outputs :search-attribution])
                  _ (workspace/record-search! !workspace {:queries (:queries args)
                                                          :filter-by filter-by
                                                          :chunks effective-chunks
                                                          :new-count new-count
                                                          :attribution attribution
                                                          :fallback? needs-unfiltered-fallback?})
                  ;; F4: optionally auto-read the top-K chunks by merged rank
                  ;; into workspace before returning the search summary. Off
                  ;; by default (auto-read-top-k=0); enable via
                  ;; `{:builtin/agent {:auto-read-top-k 3}}` skill-params.
                  auto-read-k (agent-auto-read-top-k ambient-ctx)
                  auto-read-ids (auto-read-top-k! !workspace ambient-ctx
                                                  effective-chunks auto-read-k)
                  search-pass (count (:search-history @!workspace))
                  total-seen (count (:seen-search-chunk-ids @!workspace))
                  display-limit (agent-search-display-limit ambient-ctx)
                  base-summary (format-search-metadata-results effective-chunks new-count total-seen search-pass attribution display-limit (agent-show-snippets? ambient-ctx))
                  auto-read-note (when (seq auto-read-ids)
                                   (str "\nAuto-read top " (count auto-read-ids)
                                        " by rank into workspace: "
                                        (str/join ", " auto-read-ids)
                                        ". Do NOT re-read these via read_chunks."))]
              (str (if needs-unfiltered-fallback?
                     (str "Filtered retrieval returned 0 chunks; " retry-label ". " base-summary)
                     base-summary)
                   auto-read-note)))))

      (= "read_chunks" tool-name)
      (if (or (:read-operations-exhausted? budget)
              (:read-content-budget-exhausted? budget))
        (workspace/read-budget-exhausted-message budget)
        (let [chunk-ids (:chunk_ids args)
              doc-num (or (:doc_num args) (:doc-num args))
              chunk-range (or (:chunk_range args) (:chunk-range args))
              max-content-length (or (:max_content_length args) (:max-content-length args))
              max-content-length (when (and max-content-length (integer? max-content-length) (pos? max-content-length))
                                   max-content-length)]
          (try
            (let [workspace @!workspace
                  remaining-budget (:read-content-length-remaining budget)
                  {:keys [selected-chunk-ids selected-range raw-chunks read-source
                          prefetch-skipped-count skipped-non-supporting-chunk-ids]}
                  (cond
                    (seq chunk-ids)
                    (let [{:keys [selected-chunk-ids skipped-non-supporting-chunk-ids]}
                          (workspace/suppress-reread-chunk-ids workspace chunk-ids)
                          {:keys [selected-chunk-ids skipped-chunk-ids]}
                          (workspace/select-chunk-ids-within-budget workspace selected-chunk-ids budget)
                          ;; Server-side SMALL-DOC expansion (replaces the
                          ;; per-prompt instruction): if any selected chunk
                          ;; belongs to a doc with `:total-chunks` ≤ 6,
                          ;; widen the read to cover the whole doc so the
                          ;; LLM sees adjacent chunks for free. Falls back
                          ;; to per-chunk reads when total-chunks is
                          ;; unknown or the doc is large.
                          {:keys [chunk-id-reads doc-range-reads
                                  short-doc-promotions]}
                          (workspace/expand-short-doc-reads workspace selected-chunk-ids)
                          common-opts {:tenant (:tenant opts)
                                       :dataset-config-key (:dataset-config-key opts)
                                       :max_content_length max-content-length}
                          chunk-id-chunks (when (seq chunk-id-reads)
                                            (rag/retrieve-chunks-by-id
                                              docs-collection chunks-collection
                                              (mapv (fn [id] {:chunk_id id}) chunk-id-reads)
                                              common-opts))
                          range-chunks (when (seq doc-range-reads)
                                         (mapcat
                                           (fn [{:keys [doc-num from to]}]
                                             (rag/retrieve-chunks-by-range
                                               docs-collection chunks-collection
                                               doc-num from to common-opts))
                                           doc-range-reads))
                          combined-raw (->> (concat chunk-id-chunks range-chunks)
                                            ;; Dedup chunks by chunk_id while
                                            ;; preserving the first-seen order
                                            ;; (chunk-id reads ran first; their
                                            ;; rank-order matters more than
                                            ;; the range's positional order).
                                            (reduce (fn [{:keys [seen acc]} c]
                                                      (let [cid (:chunk_id c)]
                                                        (if (contains? seen cid)
                                                          {:seen seen :acc acc}
                                                          {:seen (conj seen cid)
                                                           :acc (conj acc c)})))
                                                    {:seen #{} :acc []})
                                            :acc)]
                      {:selected-chunk-ids (mapv :chunk_id combined-raw)
                       :selected-range nil
                       :prefetch-skipped-count (count skipped-chunk-ids)
                       :skipped-non-supporting-chunk-ids skipped-non-supporting-chunk-ids
                       :raw-chunks combined-raw
                       :read-source (cond-> {:chunk-ids selected-chunk-ids}
                                      (pos? short-doc-promotions)
                                      (assoc :short-doc-promotions short-doc-promotions
                                             :doc-range-reads doc-range-reads))})

                    (and doc-num chunk-range)
                    (let [{:keys [selected-range prefetch-skipped-count]} (workspace/plan-range-read workspace doc-num chunk-range budget)]
                      {:selected-chunk-ids nil
                       :selected-range selected-range
                       :prefetch-skipped-count prefetch-skipped-count
                       :raw-chunks (if selected-range
                                     (rag/retrieve-chunks-by-range
                                      docs-collection chunks-collection
                                      doc-num (:from selected-range) (:to selected-range)
                                      {:tenant (:tenant opts) :dataset-config-key (:dataset-config-key opts)
                                       :max_content_length max-content-length})
                                     [])
                       :read-source {:doc-num doc-num
                                     :chunk-range selected-range}})

                    (and doc-num (nil? chunk-range))
                    (let [default-range {:from 0 :to 4}
                          {:keys [selected-range prefetch-skipped-count]} (workspace/plan-range-read workspace doc-num default-range budget)]
                      {:selected-chunk-ids nil
                       :selected-range selected-range
                       :prefetch-skipped-count prefetch-skipped-count
                       :raw-chunks (if selected-range
                                     (rag/retrieve-chunks-by-range
                                      docs-collection chunks-collection
                                      doc-num (:from selected-range) (:to selected-range)
                                      {:tenant (:tenant opts) :dataset-config-key (:dataset-config-key opts)
                                       :max_content_length max-content-length})
                                     [])
                       :read-source {:doc-num doc-num
                                     :chunk-range selected-range
                                     :default-range? true}})

                    :else
                    {:selected-chunk-ids []
                     :selected-range nil
                     :prefetch-skipped-count 0
                     :skipped-non-supporting-chunk-ids []
                     :raw-chunks []
                     :read-source {:chunk-ids []}})
                  {:keys [kept-chunks trimmed-chunks consumed-content-length]}
                  (workspace/trim-chunks-to-budget raw-chunks remaining-budget)
                  skipped-note (skipped-non-supporting-read-note skipped-non-supporting-chunk-ids)
                  budget-note (workspace/read-budget-note {:returned-count (count kept-chunks)
                                                           :prefetch-skipped-count prefetch-skipped-count
                                                           :postfetch-trimmed-count (count trimmed-chunks)
                                                           :remaining-budget remaining-budget})
                  cumulative-content-length (+ (:read-content-length workspace) consumed-content-length)]
              (cond
                (and (seq chunk-ids)
                     (empty? selected-chunk-ids))
                ;; Distinguish three cases so the LLM sees the real blocker:
                ;;   (a) every requested chunk was previously non-supporting
                ;;       → emit only the skipped-note
                ;;   (b) some were non-supporting AND budget dropped the rest
                ;;       → emit BOTH notes so the LLM knows budget is now the
                ;;         constraint, not just chunk selection
                ;;   (c) none were non-supporting, budget dropped everything
                ;;       → emit only the budget-too-small message
                (let [survivors-before-budget (- (count chunk-ids)
                                                 (count (or skipped-non-supporting-chunk-ids [])))
                      some-suppressed? (seq skipped-non-supporting-chunk-ids)
                      budget-dropped-rest? (pos? survivors-before-budget)]
                  (cond
                    (and some-suppressed? budget-dropped-rest?)
                    (str skipped-note
                         "\n\n"
                         (workspace/read-budget-too-small-message budget))

                    some-suppressed?
                    skipped-note

                    :else
                    (workspace/read-budget-too-small-message budget)))

                (and doc-num (nil? selected-range))
                (if (zero? remaining-budget)
                  (workspace/read-budget-exhausted-message budget)
                  (workspace/read-budget-too-small-message budget))

                (and (seq raw-chunks) (empty? kept-chunks))
                (workspace/read-budget-too-small-message budget)

                :else
                (do
                  (when-not (:evidence-plan @!workspace)
                    (workspace/ensure-evidence-plan! !workspace
                                                     (or (:query ambient-ctx) (last (:queries workspace)) "")
                                                     (or (:last-query-intent workspace)
                                                         (workspace/infer-query-intent (or (:query ambient-ctx) "")
                                                                                       conversation-history))))
                  (workspace/record-read! !workspace
                                          (cond-> read-source
                                            max-content-length (assoc :max-content-length max-content-length))
                                          kept-chunks)
                  (workspace/add-chunks-to-workspace! !workspace kept-chunks)
                  (workspace/queue-pending-eval-chunks! !workspace kept-chunks)
                  (format-read-result kept-chunks
                                      max-content-length
                                      consumed-content-length
                                      cumulative-content-length
                                      budget-note
                                      skipped-note))))
            (catch Exception e
              (workspace/record-backend-issue! !workspace
                                               {:source :typesense
                                                :tool "read_chunks"
                                                :issue-type :read-failed
                                                :message (.getMessage e)
                                                :details (cond-> {}
                                                           (seq chunk-ids) (assoc :chunk-ids (vec chunk-ids))
                                                           doc-num (assoc :doc-num doc-num)
                                                           chunk-range (assoc :chunk-range chunk-range))})
              (str "Error executing read_chunks: " (.getMessage e))))))

      (= "plan_queries" tool-name)
      (let [query (:query args)
            query-intent (workspace/infer-query-intent query conversation-history)
            _ (workspace/record-query-intent! !workspace query-intent)
            result (execute-sub-skill
                    :builtin/query-planner
                    ;; Thread phrases-collection so corpus-aware expansion modes
                    ;; can harvest real corpus vocabulary. Without it
                    ;; corpus-aware-expand hits its `(when phrases-collection …)`
                    ;; guard and SILENTLY falls back to blind — the agent path
                    ;; never received it, so :corpus-aware-2hop was a no-op
                    ;; everywhere except the debug endpoint (which passes it).
                    {:query query
                     :conversation-history (or conversation-history [])
                     :phrases-collection phrases-collection}
                    opts)
            _ (when-let [m (get-in result [:metadata :model-used])]
                (swap! !workspace assoc :last-sub-skill-model m))
            planned-queries (or (get-in result [:outputs :queries])
                                (get-in result [:outputs :search-phrases])
                                [])
            planned-user-intent (get-in result [:outputs :user-intent])
            queries (workspace/planned-query-batch query conversation-history planned-queries [])]
        (when (seq queries)
          (swap! !workspace update :queries
                 (fn [existing] (vec (distinct (concat existing queries))))))
        ;; Slice 23: stash planner's :user-intent for subsequent search calls
        ;; in the same turn. The search tool will inject it into retrieval.
        (when planned-user-intent
          (swap! !workspace assoc :last-user-intent planned-user-intent))
        ;; Stash the planner's FULL corpus-grounded output (not just the take-6
        ;; batch that gets searched). The insufficiency/refinement path
        ;; (next-research-suggestions) reuses the un-searched corpus phrases so
        ;; re-searches stay corpus-grounded instead of falling back to generic
        ;; deterministic variants. (next-research-suggestions is a pure workspace
        ;; fn with no service access, so it can't re-invoke the planner itself.)
        (when (seq planned-queries)
          (swap! !workspace assoc :last-planner-phrases (vec planned-queries)))
        (format-plan-result result queries))

      (= "inspect_filters" tool-name)
      (try
        (let [ts-settings (ts-utils/make-ts-settings
                           {:tenant (:tenant opts)
                            :dataset-config-key (:dataset-config-key opts)})
              collection-schema (ts-client/retrieve-collection ts-settings docs-collection)
              available-fields (collection-schema->filter-fields collection-schema)
              fields-by-name (into {} (map (juxt :field identity) available-fields))
              requested-fields (->> (or (:fields args) [])
                                    (map str)
                                    (filter #(contains? fields-by-name %))
                                    distinct
                                    vec)
              max-options (or (:max-options args) (:max_options args) 10)
              facet-options
              (when (seq requested-fields)
                (let [filter-map {:fields (mapv (fn [field]
                                                  {:type :multiselect
                                                   :expanded? false
                                                   :field field
                                                   :selected-options #{}
                                                   :value-type (:value-type (get fields-by-name field))})
                                                requested-fields)
                                  :max-options max-options}
                      facets (rag/fetch-facets
                              {:docs-collection docs-collection}
                              filter-map
                              {:tenant (:tenant opts)
                               :dataset-config-key (:dataset-config-key opts)})]
                  (into {}
                        (map (fn [{:keys [field options]}]
                               [field (->> options
                                           (take max-options)
                                           (map #(select-keys % [:name :count]))
                                           vec)]))
                        (:ui/fields facets))))]
          (format-filter-inspection-result
           {:available-fields available-fields
            :facet-options facet-options}))
        (catch Exception e
          (workspace/record-backend-issue! !workspace
                                           {:source :typesense
                                            :tool "inspect_filters"
                                            :issue-type :inspect-filters-failed
                                            :message (.getMessage e)
                                            :details {:collection docs-collection
                                                      :fields (vec (or (:fields args) []))
                                                      :max-options (or (:max-options args) (:max_options args) 10)}})
          (str "Error executing inspect_filters: " (.getMessage e))))

      (= "rerank_results" tool-name)
      (let [workspace @!workspace
            workspace-chunks (workspace/get-workspace-chunks !workspace)]
        (if (and (empty? workspace-chunks)
                 (or (seq (:search-history workspace))
                     (seq (:read-history workspace))))
          (workspace/format-read-guidance workspace "rerank_results")
          ;; Re-fetch full content for any chunks that were truncated during skim reads
          (let [truncated-ids (->> workspace-chunks
                                   (filter #(str/ends-with? (or (:content_markdown %) "") "\n[truncated]"))
                                   (mapv #(hash-map :chunk_id (:chunk_id %))))
                refetched (when (seq truncated-ids)
                            (rag/retrieve-chunks-by-id
                             docs-collection chunks-collection truncated-ids
                             {:tenant (:tenant opts) :dataset-config-key (:dataset-config-key opts)}))
                full-content-by-id (when (seq refetched)
                                     (into {} (map (juxt :chunk_id :content_markdown) refetched)))
                resolved-chunks (if full-content-by-id
                                  (mapv (fn [chunk]
                                          (if-let [full (get full-content-by-id (:chunk_id chunk))]
                                            (assoc chunk :content_markdown full)
                                            chunk))
                                        workspace-chunks)
                                  workspace-chunks)]
            ;; Update workspace with full content so synthesis also sees it
            (when full-content-by-id
              (swap! !workspace update :chunks
                     (fn [chunks-map]
                       (reduce-kv (fn [m cid content]
                                    (if (contains? m cid)
                                      (assoc-in m [cid :content_markdown] content)
                                      m))
                                  chunks-map
                                  full-content-by-id))))
            (let [result (execute-sub-skill
                          :builtin/rerank
                          {:chunks resolved-chunks
                           :query (:query args)
                           :docs-collection docs-collection}
                          opts)
                  reranked (or (get-in result [:outputs :chunks])
                               (get-in result [:outputs :reranked-chunks])
                               [])
                  context-docs (get-in result [:outputs :context-docs] [])]
              (swap! !workspace assoc
                     :reranked-chunks reranked
                     :context-docs context-docs)
              (format-rerank-result result)))))

      (= "generate_response" tool-name)
      (let [workspace @!workspace
            workspace-chunks (workspace/get-workspace-chunks !workspace)]
        (if (and (empty? workspace-chunks)
                 (or (seq (:search-history workspace))
                     (seq (:read-history workspace))))
          (workspace/format-read-guidance workspace "generate_response")
          (let [context-docs (:context-docs workspace)
                result (execute-sub-skill
                        :builtin/synthesis
                        {:query (:query args)
                         :context-docs (if (seq context-docs)
                                         (mapv #(update % :page_content strip-page-markers) context-docs)
                                         (mapv (fn [chunk]
                                                 {:page_content (strip-page-markers (:content_markdown chunk))
                                                  :metadata {:source (:chunk_id chunk)}})
                                               (take 10 workspace-chunks)))}
                        opts)]
            (when-let [m (get-in result [:metadata :model-used])]
              (swap! !workspace assoc :last-sub-skill-model m))
            (when-let [generated-response (get-in result [:outputs :response])]
              (swap! !workspace assoc :last-generated-response generated-response))
            (when-let [prompts (get-in result [:outputs :prompts])]
              (swap! !workspace assoc :last-synthesis-prompts prompts))
            (when-let [citations (get-in result [:outputs :citations])]
              (swap! !workspace assoc :citations citations))
            (when-let [citation-index (get-in result [:outputs :citation-index])]
              (swap! !workspace assoc :citation-index citation-index))
            (when-let [cv (get-in result [:outputs :citation-validation])]
              (swap! !workspace assoc :citation-validation cv))
            (format-generate-result result))))

      :else
      (str "Unknown tool: " tool-name ". Available tools: "
           (str/join ", " (available-tool-names ambient-ctx))))))))

(defn effective-tool-parameters
  "Compute effective parameters for a tool call for trace/debug visibility.
   These are the parameters that will be passed to sub-skills after merging
   configured per-skill defaults with explicit tool-call arguments."
  [tool-name args ambient-ctx]
  (let [{:keys [base-tool-name ambient-ctx dataset-ref]} (resolve-tool-binding tool-name ambient-ctx)
        tool-name base-tool-name
        {:keys [opts]} ambient-ctx]
    (cond
      (#{"search" "search_documents"} tool-name)
      (let [{caller-filter :caller filter-by :filter-by} (search-filters args opts)
            primary (merged-sub-skill-parameters :builtin/retrieval opts {:filter-by filter-by
                                                                          :metadata-only true})]
        (cond-> {:sub-skill :builtin/retrieval
                 :primary primary}
          dataset-ref
          (assoc :dataset-ref dataset-ref)
          (not= (:fields filter-by) (:fields caller-filter))
          (assoc :fallback-when-empty
                 (merged-sub-skill-parameters :builtin/retrieval opts {:filter-by caller-filter}))))

      (= "read_chunks" tool-name)
      {:sub-skill :builtin/read-chunks
       :dataset-ref dataset-ref
       :primary {:chunk-ids (:chunk_ids args)
                 :doc-num (or (:doc_num args) (:doc-num args))
                 :chunk-range (or (:chunk_range args) (:chunk-range args))
                 :max-content-length (or (:max_content_length args) (:max-content-length args))
                 :local-read-signals? true}}

      (= "plan_queries" tool-name)
      {:sub-skill :builtin/query-planner
       :primary (merged-sub-skill-parameters :builtin/query-planner opts nil)}

      (= "rerank_results" tool-name)
      {:sub-skill :builtin/rerank
       :primary (merged-sub-skill-parameters :builtin/rerank opts nil)}

      (= "generate_response" tool-name)
      {:sub-skill :builtin/synthesis
       :primary (merged-sub-skill-parameters :builtin/synthesis opts nil)}

      (= "inspect_filters" tool-name)
      {:sub-skill :builtin/inspect-filters
       :dataset-ref dataset-ref
       :primary {:fields (vec (or (:fields args) []))
                 :max-options (or (:max-options args) (:max_options args) 10)}}

      :else
      {:sub-skill :unknown
       :primary nil})))

(defn execute-tool-call
  "Execute a single tool call and return formatted result.

   Four-argument arity preserves the legacy string return value used by tests
   and older call sites. Five-argument arity returns a timing envelope for the
   agent trace pipeline."
  ([tool-name args !workspace ambient-ctx]
   (execute-tool-call* tool-name args !workspace ambient-ctx))
  ([tool-name args !workspace ambient-ctx iteration]
   (let [{:keys [base-tool-name]} (resolve-tool-binding tool-name ambient-ctx)
         start-time (System/currentTimeMillis)
         captured (llm-client/capture-usage
                    #(execute-tool-call* tool-name args !workspace ambient-ctx))
         duration-ms (- (System/currentTimeMillis) start-time)
         {:keys [stage sub-skill]} (tool-stage-info base-tool-name)]
     (merge {:result-text (:result captured)
             :duration-ms duration-ms
             :iteration iteration
             :stage stage
             :tool (or base-tool-name tool-name)
             :sub-skill sub-skill}
            (usage-fields stage captured)))))

(defn execute-tool-call-pure
  "Pure-shape entry point: take `workspace-in` as a value, return the timing
   envelope plus `:workspace-out` (the workspace after the tool call's
   recorded effects). Used when threading workspace explicitly through a
   graph composition.

   The imperative `execute-tool-call*` body still runs against a local atom
   inside this fn; semantics are unchanged, but no atom escapes to callers."
  [tool-name args workspace-in ambient-ctx iteration]
  (let [{:keys [base-tool-name]} (resolve-tool-binding tool-name ambient-ctx)
        !ws (atom workspace-in)
        start-time (System/currentTimeMillis)
        captured (llm-client/capture-usage
                   #(execute-tool-call* tool-name args !ws ambient-ctx))
        duration-ms (- (System/currentTimeMillis) start-time)
        {:keys [stage sub-skill]} (tool-stage-info base-tool-name)]
    (merge {:result-text (:result captured)
            :duration-ms duration-ms
            :iteration iteration
            :stage stage
            :tool (or base-tool-name tool-name)
            :sub-skill sub-skill
            :workspace-out @!ws}
           (usage-fields stage captured))))

(defn build-tool-result-message
  "Build a tool result message for the LLM."
  [tool-call-id result-text]
  {:role "tool"
   :tool_call_id tool-call-id
   :content (or result-text "")})

(defn normalize-tool-call-for-request
  "Normalize assistant tool calls before echoing them back to the API.
   Keeps only OpenAI request fields and coerces arguments to a JSON string."
  [tool-call]
  (let [fn-name (get-in tool-call [:function :name])
        fn-args (get-in tool-call [:function :arguments])]
    {:id (:id tool-call)
     :type "function"
     :function {:name fn-name
                :arguments (if (string? fn-args)
                             fn-args
                             (json/write-str (or fn-args {})))}}))

;; =============================================================================
;; Phase 2.1: per-tool dispatch as a registered skill.
;; =============================================================================
;;
;; The agent loop previously called execute-tool-call inline via mapv. Each
;; tool dispatch is now a standard skill execution: the registry resolves the
;; skill, validates the context, applies IO validation if enabled, wraps any
;; thrown exceptions, and stamps duration metadata. The skill body itself
;; delegates to the same execute-tool-call entry point, so semantics are
;; unchanged.
;;
;; The workspace atom and ambient-ctx are passed via the ctx outside the
;; :inputs map (atoms and rich contexts don't fit the standard input
;; validation model). Inputs declare only the plain-data tool name + args +
;; iteration.

(def agent-tool-call-metadata
  {:skill-id :builtin/agent-tool-call
   :name "Agent Tool Dispatch"
   :description "Dispatch a single LLM-emitted tool call (search / read_chunks / rerank_results / generate_response / inspect_filters / etc.) against the agent's workspace. Used by :builtin/agent's ReAct loop."
   :category :orchestration
   :inputs [:tool-name :args :iteration]
   ;; THIRD ALLOWLIST IN SERIES (#25). A skill's :outputs filters what the body
   ;; returns, so execute-tool-call-pure produced :usage correctly and this
   ;; declaration silently discarded it — after normalize-stage-timing-entry
   ;; and the CSV column list, both of which drop unnamed keys the same way.
   ;; The usage keys must be declared here or the collector is inert on every
   ;; path that dispatches tools through this skill, which is the bundled one.
   :outputs [:result-text :duration-ms :iteration :stage :tool :sub-skill :workspace-out
             :usage :usage-writes :usage-expected?]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :tool-dispatch}})

(defn execute-agent-tool-call
  "Skill body for :builtin/agent-tool-call. Reads :tool-name, :args, and
   :iteration from ctx :inputs; reads workspace and ambient-ctx from the
   top-level ctx so they can be passed as references without input validation
   tripping on atom types.

   New contract: ctx :workspace-in carries the workspace as a plain map; the
   skill returns :workspace-out in outputs so callers can thread it explicitly
   (graph composition).

   Legacy contract: ctx :workspace is a workspace atom; the skill snapshots
   it, runs the pure dispatch, then resets! the atom to the new value so
   downstream code that still expects atomic side effects keeps observing
   them. Outputs still include :workspace-out — that's what the graph runner
   reads."
  [{:keys [inputs ambient-ctx workspace workspace-in] :as ctx}]
  (let [{:keys [tool-name args iteration]} inputs
        pure-mode? (contains? ctx :workspace-in)
        ws-value (if pure-mode? workspace-in @workspace)
        envelope (execute-tool-call-pure tool-name args ws-value ambient-ctx iteration)]
    (when (and (not pure-mode?) workspace)
      (reset! workspace (:workspace-out envelope)))
    (skills/success-result envelope {})))

(def agent-tool-call-skill
  {:metadata agent-tool-call-metadata
   :execute execute-agent-tool-call})

(defn register!
  "Register the agent-internal tool-dispatch skill. Idempotent."
  []
  (skills/register-skill! agent-tool-call-skill))

;; Eager registration at namespace load. The agent loop calls
;; :builtin/agent-tool-call through skills/execute-skill, so the skill needs to
;; be in the registry whenever this namespace is loaded — including in test
;; runs that don't go through digdir.skills.init/initialize!. register-skill!
;; is idempotent, so init/initialize! re-running it is harmless.
(register!)
