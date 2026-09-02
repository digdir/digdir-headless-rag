(ns digdir.skills.builtin.rerank-regime-parity-test
  "Guards for #455: the two skills that feed `digdir.rag.rerank/rerank-chunks`
   must agree on which reranker-input regime it runs.

   `rerank-chunks` builds the payload it sends to ColBERT one of two ways,
   chosen by `(:rerankWindowing params)`: Lever A (title/metadata prefix in
   full, remaining budget spent on the best-matching content window) or
   `truncate-head-tail`, which keeps the two ends of a chunk and drops the
   middle. Nothing logs which one ran, and both produce a well-formed ranking,
   so a path left on the losing regime degrades silently in proportion to
   document length.

   That is exactly what happened: Lever A was promoted on the retrieval path in
   2026-06 and the standalone rerank skill was not touched, so for three months
   the answer was `:builtin/rerank` for one caller and `:builtin/retrieval` for
   another. These guards pin the invariant rather than the tuning: the numbers
   are compared BETWEEN the paths, never against a literal, so 2000 stays free
   to move as long as it moves on both.

   Each guard has been seen red — by removing `:rerankWindowing` from the
   standalone skill (parity fails), by re-declaring its default as
   `(or windowing true)` (the explicit-false guard fails), and by pointing the
   capture at a call that never reranks (the instrument fails)."
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.rag.core :as rag]
            [digdir.rag.rerank :as rerank]
            [digdir.skills.builtin.rerank :as rerank-skill]
            [digdir.skills.builtin.retrieval :as retrieval]))

;; ---------------------------------------------------------------------------
;; Instrument
;; ---------------------------------------------------------------------------

(def ^:private a-chunk
  "Carries :content_markdown so retrieval's `needs-fetch?` stays false — the
   guard is about parameter resolution, not about chunk materialisation."
  {:chunk_id "c1" :content_markdown "some content"})

(defn- capture-rerank-call
  "Run `f` with the single seam both skills funnel into stubbed out, and return
   the params map `rerank-chunks` was handed.

   Throws when the call never happened: a path that quietly skips reranking
   would otherwise hand back nothing and compare equal to any other path that
   also handed back nothing."
  [f]
  (let [!params (atom ::never-called)]
    (with-redefs [rerank/rerank-chunks (fn [_chunks params]
                                         (reset! !params params)
                                         {:reranked-chunks [] :used-chunks [] :used-docs []})]
      (f))
    (let [params @!params]
      (when (= ::never-called params)
        (throw (ex-info "rerank-chunks was never called — this guard measured nothing" {})))
      params)))

(defn- standalone-regime
  "What `:builtin/rerank` hands the reranker for the given skill parameters."
  [parameters]
  (capture-rerank-call
    #(rerank-skill/execute-rerank
       {:inputs {:chunks [a-chunk] :query "q" :docs-collection "docs"}
        :parameters parameters
        :skill-params {:tenant "t"}})))

(defn- retrieval-regime
  "What `:builtin/retrieval`'s single-list rerank hands the reranker."
  [opts]
  (capture-rerank-call
    #(#'retrieval/apply-colbert-rerank [a-chunk] ["q"] "docs" "chunks" {:tenant "t"} opts)))

(defn- per-strategy-regime
  "What `:builtin/retrieval`'s per-strategy rerank hands the reranker. It
   declares the pair a second time, so it can drift on its own."
  [opts]
  (capture-rerank-call
    #(with-redefs [rag/retrieve-chunks-by-id (fn [_docs _chunks _hits _opts] [a-chunk])]
       (#'retrieval/apply-per-strategy-colbert-rerank
         [[{:chunk_id "c1"}]] ["q"] "docs" "chunks" {:tenant "t"} opts))))

(def ^:private regime-keys
  "The reranker-input regime: which construction runs, and the budget it gets.
   Both halves travel together — windowing at the old budget is a third regime,
   neither the validated arm nor the historical baseline."
  [:rerankWindowing :rerankMaxChunkLength])

;; ---------------------------------------------------------------------------
;; Guards
;; ---------------------------------------------------------------------------

(deftest every-call-path-resolves-the-same-reranker-regime
  (let [standalone (standalone-regime {})
        single     (retrieval-regime {})
        per-strat  (per-strategy-regime {})]
    (testing "each path states a windowing decision at all"
      ;; Without this, a change that dropped the key from BOTH paths would
      ;; leave two empty selections comparing equal, and the parity assertion
      ;; below would pass on a total regression.
      (is (contains? standalone :rerankWindowing)
          ":builtin/rerank must tell the reranker which construction to use")
      (is (contains? single :rerankWindowing))
      (is (contains? per-strat :rerankWindowing)))
    (testing "and the paths agree on it, and on the budget it spends"
      (is (= (select-keys single regime-keys)
             (select-keys standalone regime-keys))
          "the standalone rerank skill and the main retrieval path diverged")
      (is (= (select-keys single regime-keys)
             (select-keys per-strat regime-keys))
          "retrieval's per-strategy rerank diverged from its single-list rerank"))))

(deftest windowing-is-expressible-on-the-standalone-rerank-skill
  (testing "declared, so a graph author can find the knob without reading rag/rerank.clj"
    (is (= :boolean (get-in rerank-skill/rerank-metadata [:parameters :windowing]))))
  (testing "and an explicit false reaches the reranker on every path"
    ;; The reachability half of #455. `(or windowing true)` satisfies every
    ;; other assertion in this namespace while making the knob a no-op in the
    ;; only direction anyone would turn it — this is what rules that out.
    (is (false? (:rerankWindowing (standalone-regime {:windowing false}))))
    (is (false? (:rerankWindowing (retrieval-regime {:rerank-windowing false}))))
    (is (false? (:rerankWindowing (per-strategy-regime {:rerank-windowing false}))))))

(deftest the-capture-fails-loudly-when-nothing-reranks
  ;; The instrument on its own: a path that never reaches the reranker must be
  ;; distinguishable from one that reaches it with matching parameters.
  (is (thrown? clojure.lang.ExceptionInfo
        (capture-rerank-call (fn [] :nothing-reranked-here)))))
