(ns digdir.setup.demo-dataset-test
  "The shipped default dataset must actually materialize (#447).

   Not a shape check. It runs the real `dataset-config->loader-config`, which
   calls `require-explicit-materialization-config!` — the function that throws
   \"Pipeline materialization config incomplete\" for any missing property.
   Fallback defaults were removed for ALL pipelines, so a `:folder` dataset must
   supply THIRTEEN properties explicitly and there is nothing to fall back on.

   That is the failure this guards: a dataset shipped with the product that
   throws the first time anyone materializes it."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.pipeline.materialization :as materialization]
            [digdir.setup.demo-dataset :as demo]))

(deftest demo-dataset-materializes
  (testing "every required property is present — checked by the real contract"
    (let [required (materialization/required-execution-properties (demo/pipeline-config))
          missing (->> (keys required)
                       (remove #(contains? (demo/pipeline-config) %))
                       sort vec)]
      (testing "the contract is not vacuous"
        ;; A source-type that resolved to no required keys would make the
        ;; assertion below pass over an empty list.
        (is (< 10 (count required))
            (str "expected a populated required set, saw " (count required))))
      (is (empty? missing)
          (str "the shipped demo dataset is missing required materialization "
               "properties and would throw on first materialization: "
               (pr-str missing)))))

  (testing "and it converts to a loader config without throwing"
    ;; The end-to-end version of the above: this is the call the executor makes.
    (let [loader (materialization/dataset-config->loader-config (demo/pipeline-config))]
      (is (map? loader))
      (is (= :header-based (:chunks/strategy loader)))
      (is (= 333 (:chunks/minimum-length loader)))
      (is (= (demo/corpus-directory) (:folder/path loader)))))

  (testing "chunks are deliberately NOT sub-split"
    ;; Measured: unsplit finds more answers than splitting at 850 on this corpus.
    ;; The key must be ABSENT rather than nil — `split-oversized-chunks` reads
    ;; \"absent => no-op\", and #453 made the optional key omit itself when unset
    ;; precisely so that stays true.
    (let [loader (materialization/dataset-config->loader-config (demo/pipeline-config))]
      (is (not (contains? loader :chunks/split-max-length))
          "an explicit nil here would depend on every consumer treating it as absent"))))

(deftest corpus-directory-is-read-at-call-time
  ;; The fetch script and the dataset must agree on one location, and a
  ;; deployment must be able to move it without a rebuild.
  (testing "it resolves to something usable"
    (is (seq (demo/corpus-directory))))
  (testing "and the dataset points at exactly that directory"
    (is (= (demo/corpus-directory) (:folder-path (demo/dataset-values))))))
