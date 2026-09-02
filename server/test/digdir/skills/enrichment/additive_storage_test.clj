(ns digdir.skills.enrichment.additive-storage-test
  "The additive-storage guarantee — the property the self-improvement epic (#82)
   rests on.

   Enrichment writes to a PARALLEL Typesense collection keyed by `chunk_id` and
   never mutates the base `documents` / `chunks` / `phrases` collections, so a
   bad run is discardable rather than destructive. Until now that held by
   construction and by care, which is not the same as being guaranteed.

   The property under test is *where writes land*, not what the model proposes,
   so the model is stubbed and Typesense is replaced by an in-memory fake. That
   keeps this a fast deterministic test that runs in CI, rather than an
   integration test that needs a live model and a populated index and therefore
   never runs.

   The instrument is `with-fake-typesense`: it redefines EVERY mutating fn in
   `typesense.client` — not just the two the apply path happens to use — and
   records the collection each one targeted. So a write from any step of the
   graph, including a future one, is caught rather than assumed absent."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.config.accessor :as cfg]
            [digdir.rag.retrieval :as rag]
            [digdir.rag.skills.core :as skills-core]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.enrichment.apply-questions :as apply-questions]
            [digdir.skills.enrichment.fetch-chunk-context :as fetch-chunk-context]
            [digdir.skills.enrichment.propose-questions :as propose-questions]
            [digdir.skills.enrichment.questions-graph :as questions-graph]
            [digdir.skills.enrichment.revert-chunk :as revert-chunk]
            [digdir.skills.enrichment.verify-retrieval :as verify-retrieval]
            [digdir.skills.graph.runner :as runner]
            [digdir.skills.templates.core :as templates]
            [typesense.client :as ts]
            [wkok.openai-clojure.api :as openai]))

(use-fixtures :each
  (fn [f]
    ;; The skills and skill-graph registries are process-global, and other
    ;; namespaces empty them via skills-api/reset-skills!. So this namespace has
    ;; to work from both directions:
    ;;
    ;;   register  - a load-time registration does not survive a reset, and this
    ;;               namespace errored in the full suite with "Skill not found:
    ;;               :builtin/enrichment-verify-retrieval" while passing alone;
    ;;   restore   - registering into a registry another test is about to read
    ;;               is just as bad in the other direction. Adding the graph
    ;;               made digdir.skills.init-test's every-agent-skill-graph-
    ;;               resolves fail ("expected 11, registered 1").
    ;;
    ;; So: add what the graph needs, then put both registries back exactly as
    ;; they were found. Order-independent in and out.
    (let [skills-before (set (skills-core/list-skill-ids))
          graphs-before (set (templates/list-skill-graph-ids))]
      (fetch-chunk-context/register!)
      (propose-questions/register!)
      (apply-questions/register!)
      (verify-retrieval/register!)
      (questions-graph/register!)
      (try
        (f)
        (finally
          (doseq [id (remove skills-before (skills-core/list-skill-ids))]
            (skills-core/unregister-skill! id))
          (doseq [id (remove graphs-before (templates/list-skill-graph-ids))]
            (templates/unregister-skill-graph! id)))))))

;; =============================================================================
;; The collections
;; =============================================================================

(def ^:private docs-collection "digdir_rag_docs_testhash")
(def ^:private chunks-collection "digdir_rag_chunks_testhash")
(def ^:private phrases-collection "digdir_rag_phrases_testhash")
(def ^:private enrichment-collection
  "digdir_rag_enrichment_hypothetical_questions_testhash")

(def ^:private base-collections
  "The collections enrichment must never touch."
  #{docs-collection chunks-collection phrases-collection})

(def ^:private test-chunk-id "chunk-1")

(defn- seed-store
  "A minimal but complete base index: one doc, one chunk belonging to it, one
   phrase row, and an empty enrichment collection."
  []
  ;; Field names match what the promoted path actually reads:
  ;; `:content_markdown` and `:url` off the chunk row, `:title` off the doc row
  ;; (digdir.skills.enrichment.fetch-chunk-context).
  {docs-collection [{:id "d1" :doc_num "1" :title "Om Dialogporten"}]
   chunks-collection [{:id "c1" :chunk_id test-chunk-id :doc_num "1"
                       :content_markdown "Dialogporten er en felles plattform for digital dialog."
                       :url "https://example.test/dialogporten"}]
   phrases-collection [{:id "p1" :chunk_id test-chunk-id :doc_num "1"
                        :phrase "hva er dialogporten"}]
   enrichment-collection []})

;; =============================================================================
;; The fake
;; =============================================================================

(defn- filter-values
  "Pull the values out of a Typesense filter clause for `field`, handling both
   the bracket-list form (`chunk_id:=[a,b]`) and the bare form
   (`chunk_id:=a`) that the enrichment skills use."
  [filter-by field]
  (when (string? filter-by)
    (if-let [[_ inner] (re-find (re-pattern (str field ":=\\[([^\\]]*)\\]")) filter-by)]
      (->> (str/split inner #",") (map str/trim) (remove str/blank?) set)
      (when-let [[_ v] (re-find (re-pattern (str field ":=([^&\\s\\]]+)")) filter-by)]
        #{(str/trim v)}))))

(defn- row-matches?
  [row filter-by]
  (every? (fn [field]
            (if-let [wanted (filter-values filter-by (name field))]
              (contains? wanted (str (get row field)))
              true))
          [:chunk_id :doc_num :prompt_hash]))

(defn- search-response
  [rows filter-by]
  (let [hits (if (str/blank? (str filter-by))
               rows
               (filterv #(row-matches? % filter-by) rows))]
    {:found (count hits)
     :hits (mapv (fn [r] {:document r}) hits)}))

(defmacro with-fake-typesense
  "Run `body` with Typesense replaced by an in-memory store.

   `!store` is an atom of {collection-name [row ...]}; `!writes` accumulates
   {:op :collection} for every mutating call, whatever collection it targeted.
   Every bang fn in `typesense.client` is stubbed, so an unexpected write is
   recorded rather than escaping to a real server or passing unnoticed."
  [!store !writes & body]
  `(let [record!# (fn [op# coll#] (swap! ~!writes conj {:op op# :collection coll#}))]
     (with-redefs
       [ts-utils/make-ts-settings (fn [& _#] {:uri "http://fake" :key "fake"})

        ;; --- reads: served from the store -----------------------------------
        ts/search (fn [_# coll# params#]
                    (search-response (get @~!store coll# []) (:filter_by params#)))
        ts/retrieve-document (fn [_# coll# id#]
                               (first (filterv #(= id# (:id %)) (get @~!store coll# []))))

        ;; --- document mutators ----------------------------------------------
        ts/upsert-documents! (fn [_# coll# docs#]
                               (record!# :upsert-documents! coll#)
                               (swap! ~!store update coll# (fnil into []) docs#)
                               (mapv (fn [_#] {:success true}) docs#))
        ts/upsert-document! (fn [_# coll# doc#]
                              (record!# :upsert-document! coll#)
                              (swap! ~!store update coll# (fnil conj []) doc#)
                              doc#)
        ts/create-documents! (fn [_# coll# docs#]
                               (record!# :create-documents! coll#)
                               (swap! ~!store update coll# (fnil into []) docs#)
                               (mapv (fn [_#] {:success true}) docs#))
        ts/create-document! (fn [_# coll# doc#]
                              (record!# :create-document! coll#)
                              (swap! ~!store update coll# (fnil conj []) doc#)
                              doc#)
        ts/update-documents! (fn [_# coll# _docs# _opts#]
                               (record!# :update-documents! coll#)
                               {:num_updated 0})
        ts/update-document! (fn [_# coll# _id# doc#]
                              (record!# :update-document! coll#)
                              doc#)
        ts/delete-documents! (fn [_# coll# opts#]
                               (record!# :delete-documents! coll#)
                               (let [before# (get @~!store coll# [])
                                     kept# (filterv #(not (row-matches? % (:filter_by opts#))) before#)]
                                 (swap! ~!store assoc coll# kept#)
                                 {:num_deleted (- (count before#) (count kept#))}))
        ts/delete-document! (fn [_# coll# id#]
                              (record!# :delete-document! coll#)
                              (swap! ~!store update coll#
                                     (fn [rows#] (filterv #(not= id# (:id %)) rows#)))
                              {:id id#})

        ;; --- collection / alias mutators: never expected on this path -------
        ts/create-collection! (fn [_# schema#]
                                (record!# :create-collection! (:name schema#)) schema#)
        ts/delete-collection! (fn [_# coll#] (record!# :delete-collection! coll#) {})
        ts/update-collection! (fn [_# coll# _#] (record!# :update-collection! coll#) {})
        ts/upsert-alias! (fn [_# alias# _#] (record!# :upsert-alias! alias#) {})
        ts/delete-alias! (fn [_# alias#] (record!# :delete-alias! alias#) {})
        ts/upsert-synonym! (fn [_# coll# _# _#] (record!# :upsert-synonym! coll#) {})
        ts/delete-synonym! (fn [_# coll# _#] (record!# :delete-synonym! coll#) {})
        ts/upsert-override! (fn [_# coll# _# _#] (record!# :upsert-override! coll#) {})
        ts/delete-override! (fn [_# coll# _#] (record!# :delete-override! coll#) {})]
       ~@body)))

(defmacro with-stubbed-model
  "Stub the propose step's LLM. What the model proposes is irrelevant to where
   writes land; stubbing it is what keeps this test runnable in CI."
  [& body]
  `(with-redefs
     [openai/create-chat-completion
      (fn [& _#]
        {:choices [{:message {:content (str "Hva er Dialogporten?\n"
                                            "Hvem kan bruke Dialogporten?\n"
                                            "Hvordan fungerer Dialogporten?")}}]})
      cfg/get (fn [_opts# & ks#]
                (case (vec ks#)
                  [:services :azure-openai :api-key] "test-key"
                  [:services :azure-openai :api-endpoint] "https://fake.test"
                  [:services :azure-openai :deployment-name] "test-deployment"
                  nil))
      ;; verify-retrieval is a read-only step; stub it at the retrieval boundary
      ;; so this test doesn't depend on embeddings or tenant config. Any write it
      ;; attempted would still be caught — the mutator stubs above are global.
      rag/search-chunks-by-content (fn [& _#] [])
      rag/lookup-hypothetical-questions-similar
      (fn [& _#] [{:chunk_id test-chunk-id :rank 1 :index 0}])]
     ~@body))

(defn- graph-inputs []
  {:chunk-id test-chunk-id
   :enrichment-collection-name enrichment-collection
   :chunks-collection chunks-collection
   :docs-collection docs-collection
   :tenant "test"
   :dataset-config-key "default"
   :tenant-config-key "default"
   :runtime-config-key "default"
   :suite-file nil
   :user-query "Hva er Dialogporten?"})

(defn- run-enrichment!
  "Run the promoted per-chunk enrichment graph end to end."
  [!store !writes]
  (with-fake-typesense !store !writes
    (with-stubbed-model
      (runner/run-graph questions-graph/enrich-one-chunk-graph
                        (graph-inputs)
                        {:tenant "test" :environment "dev"}))))

(defn- expected-questions [] 3)

(defn- revert-enrichment!
  "Run the revert the outer graph's batch-decide uses for a rejected chunk."
  [!store !writes]
  (with-fake-typesense !store !writes
    (revert-chunk/execute-revert-chunk
     {:inputs {:chunk-id test-chunk-id
               :collection-name enrichment-collection}
      :parameters {}
      :skill-params {:tenant "test"}})))

(defn- base-snapshot [store] (select-keys store base-collections))

(defn- writes-to-base [writes] (filterv #(contains? base-collections (:collection %)) writes))

;; =============================================================================
;; The guarantee
;; =============================================================================

(deftest enrichment-never-mutates-base-collections
  (testing "1. base collections are byte-identical after an enrichment run"
    (let [!store (atom (seed-store))
          !writes (atom [])
          before (base-snapshot @!store)]
      (run-enrichment! !store !writes)
      (is (= before (base-snapshot @!store))
          "documents / chunks / phrases must be unchanged - same counts, same records")
      (is (= [] (writes-to-base @!writes))
          "no mutating Typesense call may target a base collection")
      (doseq [coll base-collections]
        (is (= (count (get before coll)) (count (get @!store coll)))
            (str "row count changed in " coll))))))

(deftest enrichment-rows-land-in-the-parallel-collection
  (testing "2. the write actually happened - this test is not passing vacuously"
    (let [!store (atom (seed-store))
          !writes (atom [])]
      (let [result (run-enrichment! !store !writes)
            outputs (:outputs result)]
        ;; Guard against a vacuous pass: every step of the promoted graph must
        ;; actually have run. This is also #89's open acceptance criterion —
        ;; nobody had run the promoted path end to end.
        (is (= "Dialogporten er en felles plattform for digital dialog."
               (:chunk-content outputs))
            ":fetch read the chunk out of the chunks collection")
        (is (= (expected-questions) (count (:questions outputs)))
            ":propose produced questions")
        (is (= (expected-questions) (:applied-count outputs))
            ":apply reported writing them")
        (is (some? (:summary outputs)) ":verify ran"))
      (let [rows (get @!store enrichment-collection)]
        (is (seq rows) "enrichment rows must be written to the parallel collection")
        (is (every? #(= test-chunk-id (:chunk_id %)) rows)
            "every enrichment row is keyed by chunk_id")
        (is (every? #(string? (:question %)) rows))
        (is (seq (filterv #(= enrichment-collection (:collection %)) @!writes))
            "the writes were aimed at the enrichment collection")))))

(deftest revert-removes-enrichment-and-still-leaves-base-untouched
  (testing "3. revert cleans up its own collection and reaches into no other"
    (let [!store (atom (seed-store))
          !writes (atom [])
          before (base-snapshot @!store)]
      (run-enrichment! !store !writes)
      (is (seq (get @!store enrichment-collection)) "precondition: rows were applied")

      (let [!revert-writes (atom [])]
        (revert-enrichment! !store !revert-writes)
        (is (empty? (get @!store enrichment-collection))
            "revert removes the enrichment rows")
        (is (= [] (writes-to-base @!revert-writes))
            "revert must not reach into a base collection to tidy up")
        (is (= before (base-snapshot @!store))
            "base collections survive the revert byte-identical")))))
