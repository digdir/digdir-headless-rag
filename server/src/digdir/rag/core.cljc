(ns digdir.rag.core
  "Facade for RAG (Retrieval-Augmented Generation) core functionality.
   This namespace delegates to specialized sub-namespaces for retrieval, reranking, and synthesis."
  (:require [digdir.rag.filters :as filters]
            [digdir.rag.merge :as merge]
            [digdir.rag.formatting :as formatting]
            #?(:clj [digdir.rag.retrieval :as retrieval])
            #?(:clj [digdir.rag.synthesis :as synthesis])
            #?(:clj [digdir.rag.rerank :as rerank])
            #?(:clj [digdir.rag.query-relaxation :as query-relaxation])
            [lambdaisland.deep-diff2 :as ddiff]))

;; =============================================================================
;; Debugging & Utility
;; =============================================================================

(defn T
  "For debugging: Input → ___ → Output"
  ([x] (prn x) x)
  ([tag x] (prn tag x) x))

(defn print-diff [old new]
  (-> (ddiff/diff old new)
      ddiff/minimize
      ddiff/pretty-print
      with-out-str
      print))

(def stage-name "DOCS_QA_RAG")

(defn rcomp [& fns]
  (apply comp (reverse fns)))

;; =============================================================================
;; Formatting (Delegated to digdir.rag.formatting)
;; =============================================================================

(defn parse-header-level [level-str]
  (formatting/parse-header-level level-str))

(defn format-header [level content]
  (formatting/format-header level content))

(defn extract-header-entries [metadata-map]
  (formatting/extract-header-entries metadata-map))

(defn format-metadata-headers [metadata]
  (formatting/format-metadata-headers metadata))

(defn truncate-head-tail [s max-len]
  (formatting/truncate-head-tail s max-len))

;; =============================================================================
;; Filters (Delegated to digdir.rag.filters)
;; =============================================================================

(defn format-filter-value [value value-type]
  (filters/format-filter-value value value-type))

#?(:clj
   (defn filter-map->typesense-filter [filter-map docs-collection-name]
     (filters/filter-map->typesense-filter filter-map docs-collection-name)))

#?(:clj
   (defn filter-map->typesense-facet-multi-search [filter-map docs-collection-name]
     (filters/filter-map->typesense-facet-multi-search filter-map docs-collection-name)))

;; =============================================================================
;; Retrieval (Delegated to digdir.rag.retrieval)
;; =============================================================================

#?(:clj
   (do
     (defn field->counts [facet_counts]
       (retrieval/field->counts facet_counts))

     (defn options [res]
       (retrieval/options res))

     (defn facet-result->ui-field [filter-field options]
       (retrieval/facet-result->ui-field filter-field options))

     (defn fetch-facets
       ([conversation-pipeline filter-map]
        (retrieval/fetch-facets conversation-pipeline filter-map))
       ([conversation-pipeline filter-map opts]
        (retrieval/fetch-facets conversation-pipeline filter-map opts)))

     (defn lookup-search-phrases-similar
       ([phrases-collection-name docs-collection-name relaxed-queries filter-by]
        (retrieval/lookup-search-phrases-similar phrases-collection-name docs-collection-name relaxed-queries filter-by))
       ([phrases-collection-name docs-collection-name relaxed-queries filter-by opts]
        (retrieval/lookup-search-phrases-similar phrases-collection-name docs-collection-name relaxed-queries filter-by opts)))

     (defn lookup-hypothetical-questions-similar
       ([questions-collection-name docs-collection-name relaxed-queries filter-by]
        (retrieval/lookup-hypothetical-questions-similar questions-collection-name docs-collection-name relaxed-queries filter-by))
       ([questions-collection-name docs-collection-name relaxed-queries filter-by opts]
        (retrieval/lookup-hypothetical-questions-similar questions-collection-name docs-collection-name relaxed-queries filter-by opts)))

     (defn lookup-verified-phrases-similar
       ([phrases-collection-name docs-collection-name relaxed-queries filter-by]
        (retrieval/lookup-verified-phrases-similar phrases-collection-name docs-collection-name relaxed-queries filter-by))
       ([phrases-collection-name docs-collection-name relaxed-queries filter-by opts]
        (retrieval/lookup-verified-phrases-similar phrases-collection-name docs-collection-name relaxed-queries filter-by opts)))

     (defn lookup-fact-assertions-similar
       ([facts-collection-name docs-collection-name relaxed-queries filter-by]
        (retrieval/lookup-fact-assertions-similar facts-collection-name docs-collection-name relaxed-queries filter-by))
       ([facts-collection-name docs-collection-name relaxed-queries filter-by opts]
        (retrieval/lookup-fact-assertions-similar facts-collection-name docs-collection-name relaxed-queries filter-by opts)))

     (defn search-chunks-by-metadata
       ([chunks-collection-name docs-collection-name relaxed-queries filter-by]
        (retrieval/search-chunks-by-metadata chunks-collection-name docs-collection-name relaxed-queries filter-by))
       ([chunks-collection-name docs-collection-name relaxed-queries filter-by opts]
        (retrieval/search-chunks-by-metadata chunks-collection-name docs-collection-name relaxed-queries filter-by opts)))

     (defn search-chunks-by-content
       ([chunks-collection-name docs-collection-name relaxed-queries filter-by]
        (retrieval/search-chunks-by-content chunks-collection-name docs-collection-name relaxed-queries filter-by))
       ([chunks-collection-name docs-collection-name relaxed-queries filter-by opts]
        (retrieval/search-chunks-by-content chunks-collection-name docs-collection-name relaxed-queries filter-by opts)))

     (defn search-docs-by-title
       ([docs-collection-name chunks-collection-name title-fields chunk-fanout relaxed-queries filter-by]
        (retrieval/search-docs-by-title docs-collection-name chunks-collection-name title-fields chunk-fanout relaxed-queries filter-by))
       ([docs-collection-name chunks-collection-name title-fields chunk-fanout relaxed-queries filter-by opts]
        (retrieval/search-docs-by-title docs-collection-name chunks-collection-name title-fields chunk-fanout relaxed-queries filter-by opts)))

     (defn get-typesense-collection [collection-name]
       (retrieval/get-typesense-collection collection-name))

     (defn retrieve-chunks-by-id
       ([docs-collection-name chunks-collection-name chunk-id-list]
        (retrieval/retrieve-chunks-by-id docs-collection-name chunks-collection-name chunk-id-list))
       ([docs-collection-name chunks-collection-name chunk-id-list opts]
        (retrieval/retrieve-chunks-by-id docs-collection-name chunks-collection-name chunk-id-list opts)))

     (defn retrieve-chunk-metadata-by-id [docs-collection-name chunks-collection-name chunk-id-list opts]
       (retrieval/retrieve-chunk-metadata-by-id docs-collection-name chunks-collection-name chunk-id-list opts))

     (defn retrieve-chunks-by-range [docs-collection-name chunks-collection-name doc-num from-idx to-idx opts]
       (retrieval/retrieve-chunks-by-range docs-collection-name chunks-collection-name doc-num from-idx to-idx opts))))

;; =============================================================================
;; Synthesis (Delegated to digdir.rag.synthesis)
;; =============================================================================

#?(:clj
   (do
     (defn system-prompt-with-date []
       (synthesis/system-prompt-with-date))

     (defn rag-generate [!dh-conn convo-id extract-search-queries full-prompt params]
       (synthesis/rag-generate !dh-conn convo-id extract-search-queries full-prompt params))

     (defn simplify-convo-topic [params]
       (synthesis/simplify-convo-topic params))))

;; =============================================================================
;; Rerank (Delegated to digdir.rag.rerank)
;; =============================================================================

#?(:clj
   (defn rerank-chunks [retrieved-chunks params]
     (rerank/rerank-chunks retrieved-chunks params)))

;; =============================================================================
;; Query Relaxation (Delegated to digdir.rag.query-relaxation)
;; =============================================================================

#?(:clj
   (defn query-relaxation [tenant prompt-rag-query-relax messages selected-model]
     (query-relaxation/query-relaxation tenant prompt-rag-query-relax messages selected-model)))

;; =============================================================================
;; Merge (Delegated to digdir.rag.merge)
;; =============================================================================

#?(:clj
   (do
     (defn normalize-ranks [results]
       (merge/normalize-ranks results))

     (defn merge-chunk-search-results [& args]
       (apply merge/merge-chunk-search-results args))))
