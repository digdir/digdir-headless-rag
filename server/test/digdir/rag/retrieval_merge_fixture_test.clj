(ns digdir.rag.retrieval-merge-fixture-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [digdir.rag.core :as rag]))

(def ^:private fixture-path "fixtures/retrieval/merge_strategy_regression.edn")

(defn- load-fixture []
  (let [resource (io/resource fixture-path)]
    (when-not resource
      (throw (ex-info (str "Missing fixture: " fixture-path) {})))
    (-> resource slurp edn/read-string)))

(defn- rank-position
  [rows chunk-id]
  (first (keep-indexed (fn [idx row]
                         (when (= chunk-id (:chunk_id row))
                           (inc idx)))
                       rows)))

(deftest merge-regression-suite
  (testing "Golden chunks stay within accepted merged rank bounds across fixture cases"
    (let [{:keys [cases]} (load-fixture)]
      (is (seq cases) "Fixture must contain at least one case")
      (doseq [{:keys [id query golden-chunk-id max-acceptable-rank phrase-hits metadata-hits content-hits]} cases]
        (let [merged (rag/merge-chunk-search-results
                      (map #(assoc % :search-type :phrase) phrase-hits)
                      (map #(assoc % :search-type :metadata) metadata-hits)
                      (map #(assoc % :search-type :content) content-hits))
              pos (rank-position merged golden-chunk-id)]
          (is (some? pos)
              (str id " (" query "): golden chunk missing from merged output"))
          (is (<= pos max-acceptable-rank)
              (str id " (" query "): expected golden rank <= "
                   max-acceptable-rank ", got " pos)))))))
