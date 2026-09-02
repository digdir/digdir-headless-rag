(ns digdir.playground.ui.observability.enrichment-inspector
  "Server-only helpers for the playground observability drawer's
   enrichment-tool inspectors.

   Today this is just the Typesense-rows inspector for
   `apply_enrichments`: query the enrichment collection by chunk_id
   and return the rows so the UI drawer can render them as a table.
   When the Phase D enrichment types land they'll grow more
   inspector fns alongside this one.

   Kept in a `.clj` (not `.cljc`) namespace because all of it touches
   the live Typesense client. The `.cljc` view-model layer in
   `digdir.playground.ui.observability.live-next` invokes these via
   Electric's `e/server` so the call dispatches on the server side."
  (:require [clojure.string :as str]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts]))

(defn- do-fetch
  [tenant collection-name ids]
  (let [settings (ts-utils/make-ts-settings (when tenant {:tenant tenant}))
        _ (when-not settings
            (throw (ex-info "No Typesense settings"
                            {:tenant tenant
                             :collection-name collection-name})))
        filter-by (str "chunk_id:=[" (str/join "," ids) "]")
        ;; Include both `question` (hypothetical-questions schema, Phase B)
        ;; and `phrase` (verified-phrases schema, Phase D1). Typesense
        ;; ignores include_fields entries that aren't in the collection's
        ;; schema, so each collection returns only its own scalar text
        ;; field. Excluding both `_vec` variants keeps the embedding
        ;; payload out of the drawer for either schema.
        resp (ts/search settings collection-name
                        {:q "*"
                         :filter_by filter-by
                         :include_fields "chunk_id,doc_num,question,phrase,model,prompt_hash,generated_at"
                         :exclude_fields "question_vec,phrase_vec"
                         :per_page (* 10 (count ids))})]
    (->> (:hits resp)
         (map :document)
         ;; Sort by chunk_id then by whichever text field the row has —
         ;; questions schema gives :question, phrases schema gives :phrase.
         (sort-by (fn [d]
                    [(or (:chunk_id d) "")
                     (or (:question d) (:phrase d) "")]))
         vec)))

(defn fetch-enrichment-rows
  "Fetch all rows in `collection-name` matching any chunk-id in
   `chunk-ids`. Returns a result map the UI drawer can dispatch on:

     {:rows [<row-map> ...]}    — happy path
     {:error \"...\"}              — Typesense / settings / network failure

   Schema fields surfaced (per `digdir.skills.enrichment.collections/hypothetical-questions-schema`):
   chunk_id, doc_num, question, model, prompt_hash, generated_at. The
   `question_vec` embedding is excluded — useless to a human reading
   the drawer.

   Empty `chunk-ids` returns `{:rows []}`. The result-map shape (vs.
   throwing) is deliberate: Electric's `e/server` blocks can't sit
   inside `try/catch` yet (`try is TODO` at macroexpand). Capturing
   the failure here means the UI just renders the right message."
  [tenant collection-name chunk-ids]
  (let [ids (->> chunk-ids
                 (remove nil?)
                 distinct
                 vec)]
    (cond
      (or (nil? collection-name) (empty? collection-name))
      {:rows []}

      (empty? ids)
      {:rows []}

      :else
      (try
        {:rows (do-fetch tenant collection-name ids)}
        (catch Throwable t
          {:error (.getMessage t)})))))
