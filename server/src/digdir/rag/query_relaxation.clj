(ns digdir.rag.query-relaxation
  "Thin shim delegating to `digdir.skills.builtin.query-planner`.

   Originally a standalone LLM query-expansion path that predated the
   query-planner skill. In slice 18 the two were unified: the planner
   skill now does intent extraction + phrase generation in one LLM
   call with retry-with-backoff, and this namespace is just a backwards-
   compat wrapper for callers that consume the legacy plain-vec-of-
   phrases shape (diagnostics tools, playground UI's `:query-relaxation`
   diagnostic key).

   New code should call `digdir.skills.builtin.query-planner` directly."
  (:require [clojure.string :as str]
            [digdir.skills.builtin.query-planner :as query-planner]))

(defn- last-user-message-text
  "Pull the user-facing text out of the last conversation-history
   message. Tolerant of the several shapes callers pass — :message/text,
   :text, :content — same fallback chain `query-planner` uses internally."
  [messages]
  (some-> messages
          last
          (#(or (:message/text %) (:text %) (:content %)))
          str/trim
          not-empty))

(defn do-query-relaxation
  "Single-pass LLM expansion. Returns a vec of phrases or nil on failure.

   Kept as a no-retry shim mostly for tests and one-off callers; the
   `query-relaxation` wrapper below adds retry-equivalent behavior via
   the planner's built-in retry-with-backoff."
  [tenant prompt-rag-query-relax messages _selected-model]
  (let [query (last-user-message-text messages)
        result (query-planner/execute-query-planner
                {:inputs {:query (or query "")
                          :conversation-history messages}
                 :parameters (cond-> {}
                               (not (str/blank? prompt-rag-query-relax))
                               (assoc :prompt prompt-rag-query-relax))
                 :skill-params {:tenant tenant}})
        queries (get-in result [:outputs :queries])]
    (when (seq queries) (vec queries))))

(defn query-relaxation
  "Public facade used by diagnostics tools and the rag/core re-export.
   Returns a vec of expansion phrases, or nil on failure (after the
   planner's built-in retry-with-backoff has been exhausted).

   The fourth argument `_selected-model` is accepted for backwards
   compatibility and currently ignored — model selection flows through
   the query-planner skill's `:model` parameter (default from tenant
   config)."
  [tenant prompt-rag-query-relax messages _selected-model]
  (do-query-relaxation tenant prompt-rag-query-relax messages _selected-model))
