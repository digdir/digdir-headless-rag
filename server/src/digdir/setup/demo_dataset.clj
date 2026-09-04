(ns digdir.setup.demo-dataset
  "The default dataset shipped with the product (#447, and the corpus #434 needs).

   A Norwegian question-answering corpus of 352 Wikipedia articles with 2,357
   NorQuAD question/answer pairs, FETCHED AT SETUP rather than checked in.

   Nothing is vendored, and that is the licence argument rather than a
   convenience: NorQuAD's CC0 covers its own questions and answers, not the
   Wikipedia prose in its `context` field, so the text comes from Wikipedia
   directly under CC BY-SA. `server/scripts/corpus/rehydrate_norquad.clj` does
   the fetch and `digdir.corpus.rehydration-test` asserts that no fetched output
   is ever committed.

   TWO SETTINGS HERE ARE MEASURED RATHER THAN CHOSEN, and both are guarded."
  (:require [digdir.docs.pipeline.search-phrases :as search-phrases]
            [digdir.setup.common :as common]
            [digdir.setup.workflow :as workflow]))

(def demo-tenant "demo")
(def demo-dataset-id "norquad-docs")
(def demo-pipeline-id "norquad-docs")

(defn corpus-directory
  "Where the rehydration script writes and the folder loader reads.

   Read at call time rather than captured, so a deployment can point it
   elsewhere without a rebuild."
  []
  (or (System/getenv "DEMO_CORPUS_DIR") "./demo-corpus"))

(def rerank-max-chunk-length
  "The reranker budget this dataset's numbers were measured under.

   MEASURED, NOT CHOSEN. Answer-visible recall@10 on this corpus — the share of
   questions whose answer text is still present in what the reranker actually
   receives — against reranker budget, with passage windowing on:

       400 (the platform seed)  33.3%
      1000                      42.2%
      2000 (the skill default)  46.2%
      4000 (this value)         47.8%
      8000                      48.0%
     32000 (uncapped)           48.0%

   4000 lands within 0.2 points of the uncapped ceiling: it loses 4 of 1,131
   retrieved gold chunks, and no larger budget recovers more than those 4. It
   costs 27% more reranker input than 2000 rather than double, because 61% of
   this corpus's chunks are already shorter than 2000 and are unaffected by the
   change. It also matches `:rerank-retrieval-max-chunk-length`, which the
   platform already seeds at 4000, so the two modes agree rather than this being
   a new number.

   ⚠️ WHAT IT DOES NOT DO. It holds 91.8% of this corpus's chunks WHOLE. It does
   not cover the corpus: chunks are not capped and the largest is 25,408
   characters. \"Covers 91.8% of chunks whole\" is the true claim.

   The platform seeds 400 for every tenant (`default-runtime-bootstrap-values`),
   which is below even an 850-character chunk, so this override is required
   rather than cosmetic. Whether that seed should change for every tenant is
   #463 and is deliberately NOT decided here."
  4000)

(def runtime-values
  "Runtime overrides for the demo tenant, merged over the platform bootstrap
   defaults. Only the reranker budget differs; everything else is inherited so
   the demo exercises the same configuration a real tenant gets."
  {:rerank-rag-max-chunk-length rerank-max-chunk-length})

(defn dataset-values
  "The materialization contract for the demo dataset.

   EVERY key here is required. `required-execution-properties` derives the
   required set from the loader key maps and `require-explicit-materialization-config!`
   throws on any that is missing — there are no fallback defaults for any
   pipeline. `demo-dataset-test` asserts this map actually materializes rather
   than trusting the list to stay complete."
  []
  {:source-type :folder
   :folder-path (corpus-directory)
   :document-limit 5000
   :document-offset 0

   ;; Header-based chunking with NO :chunk-split-max-length. Measured on this
   ;; corpus: leaving chunks unsplit finds more answers than splitting at 850
   ;; (47.8% vs 39.3% answer-visible recall@10) because a split chunk loses the
   ;; surrounding context that makes a passage answer its question. Lever B
   ;; remains available — #453 made it reachable — and is simply not used here.
   :chunk-strategy :header-based
   :chunk-minimum-length 333
   :chunk-maximum-length (* 2 128000)

   :search-phrases-model "gpt-4o"
   :search-phrases-fallback :google/gemma-3-27b-it
   :search-phrases-prompt search-phrases/default-search-phrases-prompt

   :collection-prefix "demo_norquad_"
   :parallelism-documents 3
   :parallelism-store 1
   :max-document-failures 10})

(defn pipeline-config
  "The dataset values as the materialization contract sees them — dataset values
   plus the identity the loader keys off."
  []
  (merge {:tenant demo-tenant
          :dataset-id demo-dataset-id
          :pipeline-id demo-pipeline-id
          :pipeline-name demo-pipeline-id}
         (dataset-values)))

(defn seed!
  "Create the demo tenant's dataset and runtime trees.

   Two trees, because the settings live at two roots: the materialization
   contract is `:root :dataset` and the reranker budget is `:root :runtime`.
   Seeding only the dataset would leave the reranker inheriting the platform's
   400 — the case `digdir.corpus.rerank-regime-test` exists to prevent.

   Idempotent: both bootstrap functions create-or-update.

   Requires the corpus to have been fetched already — see `bb demo-corpus`. The
   dataset points at `corpus-directory`; nothing here fetches, so that a re-seed
   does not re-download 352 articles."
  ([] (seed! demo-tenant))
  ([tenant-id]
   (workflow/bootstrap-tenant-dataset-tree!
    tenant-id "default" demo-pipeline-id
    {:dataset-id demo-dataset-id
     :dataset-values (dataset-values)})
   (workflow/bootstrap-tenant-runtime-tree!
    tenant-id
    {:dataset-id demo-dataset-id
     :runtime-values (merge workflow/default-runtime-bootstrap-values runtime-values)})
   {:tenant tenant-id
    :dataset-id demo-dataset-id
    :corpus-directory (corpus-directory)
    :rerank-max-chunk-length rerank-max-chunk-length}))

(defn -main
  "Seed the demo dataset and runtime trees, as a `-main` on the jar the image
   ships.

   ## The gap this closes (#493)

   `execute-pipeline-async!` is reachable only through the console route, and
   that route is fine — MEASURED: with an admin session it returns
   202 `{\"executionId\": …}` and the pipeline runs. What a fresh container had
   was nothing to point it at: `GET /console-api/datasets` returned
   `{\"datasets\":[]}`, because the only seeder was `bb demo-seed` and a
   container has no `bb` and no source tree.

   This namespace was already on the production classpath — calling `seed!`
   off the jar seeded the dataset and the trigger then fired. Only the door was
   missing, so this adds a door and no new mechanism.

       docker compose run --rm --no-deps --entrypoint java digdir-rag \\
         -cp /app/app.jar clojure.main -m digdir.setup.demo-dataset

   Run `digdir.setup.demo-tenant` first: the dataset is useless without the
   platform tree's Typesense settings to reach.

   ⚠️ Seeds CONFIGURATION ONLY. Nothing here fetches the corpus, so that a
   re-seed does not re-download 352 articles. A materialization triggered
   against an unfetched corpus completes cleanly having processed 0 documents
   — measured — which is a confusing success, so the corpus path is reported
   below rather than left implicit."
  [& _args]
  (println)
  (println "digdir — demo dataset (dataset + runtime trees)")
  (println "==============================================")
  (common/refuse-if-server-running! "digdir.setup.demo-dataset")
  (let [result (seed!)
        dir (java.io.File. ^String (:corpus-directory result))]
    (println (str "  ✔ tenant '" (:tenant result) "', dataset '" (:dataset-id result) "'"))
    (println (str "      corpus-directory  " (:corpus-directory result)))
    (println (str "      rerank-max-chunk-length  " (:rerank-max-chunk-length result)))
    (println)
    (if (.isDirectory dir)
      (println (str "  ✔ corpus present at " (.getAbsolutePath dir)))
      (do (println (str "  ⚠ NO CORPUS at " (.getAbsolutePath dir)))
          (println "    Materializing now will FAIL, naming that path (#556).")
          (println "    It used to COMPLETE having processed 0 documents, which")
          (println "    is the same outcome as an empty corpus and told you")
          (println "    nothing; the loader refuses instead. Fetch the corpus")
          (println "    first (`bb demo-corpus` on a machine with a source tree),")
          (println "    or mount one at that path.")))
    (println)
    (flush)
    (common/exit! 0)))
