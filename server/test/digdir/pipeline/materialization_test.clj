(ns digdir.pipeline.materialization-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.pipeline.executor :as executor]
            [digdir.pipeline.materialization :as materialization]))

(def complete-website-config
  {:tenant "digdir"
   :dataset-id "public-docs"
   :pipeline-id "altinn-docs"
   :pipeline-name "altinn-docs"
   :source-type :website
   :website-sitemap-url "https://docs.example.com/sitemap.xml"
   :website-base-url "https://docs.example.com"
   :document-limit 123
   :document-offset 7
   :chunk-strategy :header-based
   :chunk-minimum-length 250
   :chunk-maximum-length 4000
   :search-phrases-model "gpt-4.1"
   :search-phrases-fallback :google/gemma-3-27b-it
   :search-phrases-prompt "Generate phrases"
   :collection-prefix "public_docs_"
   :parallelism-documents 4
   :parallelism-store 2
   :max-document-failures 11})

(deftest test-active-deployment-tenants-require-explicit-materialization-values
  (testing "strict execution rejects missing seeded/defaulted values for deployment tenants"
    (let [err (try
                (executor/convert-pipeline-config-to-loader-format
                 {:tenant "digdir"
                  :dataset-id "public-docs"
                  :pipeline-id "altinn-docs"
                  :pipeline-name "altinn-docs"
                  :source-type :website
                  :website-base-url "https://docs.example.com"})
                nil
                (catch Exception ex
                  ex))]
      (is (some? err))
      (is (= "Pipeline materialization config incomplete" (ex-message err)))
      (is (= "digdir" (-> err ex-data :tenant)))
      (is (some #{"chunk-strategy"} (map name (-> err ex-data :missing-properties)))))))

(deftest test-active-deployment-tenants-use_explicit_dataset_values_only
  (testing "strict execution uses explicitly seeded Dataset-root values"
    (let [loader-config (executor/convert-pipeline-config-to-loader-format complete-website-config)]
      (is (= :header-based (:chunks/strategy loader-config)))
      (is (= 250 (:chunks/minimum-length loader-config)))
      (is (= 4000 (:chunks/maximum-length loader-config)))
      (is (= "gpt-4.1" (:search-phrases/model loader-config)))
      (is (= "public_docs_" (:store/coll-prefix loader-config)))
      (is (= 123 (:urls/limit loader-config)))
      (is (= 7 (:urls/offset loader-config)))
      (is (= 4 (:parallelism/documents loader-config)))
      (is (= 2 (:parallelism/store loader-config)))
      (is (= 11 (:fault-tolerance/max-document-failures loader-config))))))

(deftest test-non_target_pipelines_no_longer_receive_fallback_defaults
  (testing "non-target pipelines also stop receiving implicit fallback values"
    (let [err (try
                (executor/convert-pipeline-config-to-loader-format
                 {:tenant "ka"
                  :dataset-id "legacy"
                  :pipeline-id "legacy"
                  :pipeline-name "legacy"
                  :source-type :website
                  :website-base-url "https://legacy.example.com"})
                nil
                (catch Exception ex
                  ex))]
      (is (some? err))
      (is (= "Pipeline materialization config incomplete" (ex-message err)))
      (is (= "ka" (-> err ex-data :tenant))))))

(deftest test-optional-shared-keys-are-never-required
  ;; THE GUARD. `shared-loader-key-map` is the source of the REQUIRED property
  ;; set, so adding a key to it makes that key mandatory for every dataset and
  ;; materialization starts throwing on pipelines that were fine a commit ago.
  ;; That is how #453 shipped red: the new entry matched its siblings exactly,
  ;; and matching them exactly is what made it required.
  ;;
  ;; Nothing about a diff to those maps shows the difference, so it is asserted
  ;; here instead.
  (let [optional @#'materialization/optional-shared-loader-key-map
        required (materialization/required-execution-properties complete-website-config)]

    (testing "the guard is not vacuous"
      ;; Without this, emptying the optional map would make every assertion
      ;; below pass over nothing.
      (is (seq optional)
          "there are no optional shared keys, so this guard proves nothing")
      (is (seq required)
          "no required properties resolved - the guard is reading the wrong thing"))

    (testing "no optional shared key appears in the required set"
      (let [leaked (->> (keys optional) (filter #(contains? required %)) sort vec)]
        (is (empty? leaked)
            (str "these keys are declared optional but are being REQUIRED of every "
                 "dataset, which breaks materialization for every pipeline that "
                 "does not set them: " (pr-str leaked)
                 ". Move them out of shared-loader-key-map."))))

    (testing "a config setting no optional key still materializes"
      ;; The direct regression: `complete-website-config` sets every required
      ;; property and no optional one, which is the shape of every dataset in
      ;; the wild.
      (is (some? (executor/convert-pipeline-config-to-loader-format complete-website-config))))

    (testing "an optional key still reaches the loader when it IS set"
      ;; The other half: splitting the maps must not silently drop the key,
      ;; which is the failure the map existed to prevent in the first place.
      (let [loader (executor/convert-pipeline-config-to-loader-format
                    (assoc complete-website-config :chunk-split-max-length 850))]
        (is (= 850 (:chunks/split-max-length loader)))))

    (testing "and is absent, not nil, when it is not set"
      ;; `split-oversized-chunks` reads "absent => no-op", so an unset optional
      ;; key must not arrive as an explicit nil that a `(get m k default)` reader
      ;; would take over its default.
      (let [loader (executor/convert-pipeline-config-to-loader-format complete-website-config)]
        (is (not (contains? loader :chunks/split-max-length)))))))

;; ---------------------------------------------------------------------------
;; #506 — the same distinction, one map over
;; ---------------------------------------------------------------------------

(def complete-folder-config
  "A folder dataset setting every REQUIRED property and no optional one — the
   shape of every folder dataset that exists today, and the thing that must not
   break."
  {:tenant "demo"
   :dataset-id "local-docs"
   :pipeline-id "local-docs"
   :pipeline-name "local-docs"
   :source-type :folder
   :folder-path "/corpus/markdown/"
   :document-limit 100
   :document-offset 0
   :chunk-strategy :header-based
   :chunk-minimum-length 250
   :chunk-maximum-length 4000
   :search-phrases-model "gpt-4.1"
   :search-phrases-fallback :google/gemma-3-27b-it
   :search-phrases-prompt "Generate phrases"
   :collection-prefix "local_docs_"
   :parallelism-documents 4
   :parallelism-store 2
   :max-document-failures 11})

(deftest test-optional-source-keys-are-never-required
  ;; THE SAME GUARD AS ABOVE, for `source-loader-key-map`. #453 split the shared
  ;; map into required and optional halves and guarded it; the per-source-type
  ;; map got no equivalent, which is the whole of #506. Adding `:base-url` under
  ;; `:folder` in the required map would have made it a fourteenth mandatory
  ;; property and broken every existing folder dataset.
  ;;
  ;; Asserted across EVERY source type rather than just :folder, so the next
  ;; optional source key is covered wherever it lands.
  (let [optional @#'materialization/optional-source-loader-key-map
        source-types (keys @#'materialization/source-loader-key-map)]

    (testing "the guard is not vacuous"
      (is (seq optional)
          "there are no optional source keys, so this guard proves nothing")
      (is (seq source-types)
          "no source types resolved - the guard is reading the wrong thing"))

    (testing "no optional source key appears in the required set for its source type"
      (doseq [[source-type key-map] optional]
        (let [required (materialization/required-execution-properties {:source-type source-type})
              leaked (->> (keys key-map) (filter #(contains? required %)) sort vec)]
          ;; Control: a source type with no required properties would make the
          ;; leak check pass over nothing.
          (is (seq required)
              (str "no required properties for " source-type
                   " - the guard is not reading what it thinks it is"))
          (is (empty? leaked)
              (str "these keys are declared optional but are being REQUIRED of every "
                   (name source-type) " dataset, which breaks materialization for every "
                   "one that does not set them: " (pr-str leaked)
                   ". Move them out of source-loader-key-map.")))))))

(deftest test-folder-dataset-without-a-base-url-still-materializes
  ;; ⚠️ THE REGRESSION THE REQUIRED-KEY TRAP WOULD HAVE CAUSED, and the one
  ;; nobody notices until an existing deployment breaks: a folder corpus that
  ;; has no public address is legitimate (#504), so it must keep materializing
  ;; exactly as it did before `:folder-base-url` existed.
  (testing "it materializes at all"
    (is (some? (executor/convert-pipeline-config-to-loader-format complete-folder-config))))

  (testing "the required set for :folder did not grow"
    (is (not (contains? (materialization/required-execution-properties complete-folder-config)
                        :folder-base-url))
        "adding the key made it mandatory - every existing folder dataset now fails"))

  (testing "and the loader config carries no :base-url at all, rather than nil"
    ;; `make-url` is `(when (and base-url relative-path) ...)`, so an explicit
    ;; nil and an absent key behave the same there today - but absent is the
    ;; honest statement, and it keeps "absent => no url" true for any reader
    ;; that uses a default.
    (let [loader (executor/convert-pipeline-config-to-loader-format complete-folder-config)]
      (is (not (contains? loader :base-url))))))

(deftest test-folder-dataset-can-supply-a-base-url
  ;; The fix itself. Before #506 this value was accepted upstream and silently
  ;; dropped here, so `make-url` received nil and every document reached the
  ;; store with no url - with every individual step behaving correctly.
  (let [loader (executor/convert-pipeline-config-to-loader-format
                (assoc complete-folder-config :folder-base-url "https://example.no/docs/"))]
    (is (= "https://example.no/docs/" (:base-url loader))
        "a folder dataset still cannot supply a base URL - the value is being dropped in translation")))
