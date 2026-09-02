(ns digdir.skills.builtin.synthesis-integration-test
  "Opt-in integration tests for synthesis grounding quality using live LLM calls.

   These tests isolate synthesis from retrieval/rerank by feeding controlled
   context-docs directly into the synthesis skill. They are intentionally gated
   behind environment/config availability so they can be run repeatedly when
   evaluating model grounding quality."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.skills.builtin.synthesis :as synthesis]))

(def ^:private !fixture (atom nil))
(def ^:private !enabled? (atom false))

(defn- live-synthesis-enabled? []
  (= "true" (some-> (System/getenv "RUN_SYNTHESIS_QUALITY_INTEGRATION")
                    str/lower-case)))

(defn- services-reachable? []
  (and (live-synthesis-enabled?)
       (config-core/get-master-key)
       (try
         (some? (config-db/get-conn))
         (catch Exception _ false))))

(defn- load-fixture! []
  (let [resource (io/resource "fixtures/synthesis/digdir_arsverk_2022.edn")]
    (when-not resource
      (throw (ex-info "Missing fixture: fixtures/synthesis/digdir_arsverk_2022.edn" {})))
    (edn/read-string (slurp resource))))

(defn- setup-fixture [f]
  (if (services-reachable?)
    (do
      (reset! !fixture (load-fixture!))
      (reset! !enabled? true)
      (f))
    (println "Skipping synthesis integration tests: set RUN_SYNTHESIS_QUALITY_INTEGRATION=true and ensure CONFIG_MASTER_KEY/config DB are available.")))

(use-fixtures :once setup-fixture)

(defn- run-synthesis-case [case-entry]
  (let [{:keys [query]} @!fixture
        result (synthesis/execute-synthesis
                {:inputs {:query query
                          :context-docs (:context-docs case-entry)}
                 :parameters {:temperature 0.0}})]
    {:case (:id case-entry)
     :description (:description case-entry)
     :result result}))

(defn- chunk-cited? [result chunk-id]
  (some #(= chunk-id (:chunk-id %))
        (get-in result [:outputs :citations])))

(deftest synthesis-quality-digdir-arsverk-2022
  (when @!enabled?
    (let [{:keys [cases]} @!fixture]
      (doseq [case-entry cases]
        (testing (str "Synthesis grounds correctly for case " (name (:id case-entry)))
          (let [{:keys [result]} (run-synthesis-case case-entry)
                response (get-in result [:outputs :response] "")
                insufficient? (get-in result [:outputs :insufficient-context])
                expected-citation (:expected-citation case-entry)]
            (is (false? insufficient?)
                (str "Unexpected insufficient-context for " (:id case-entry) ": " response))
            (is (re-find #"\b326\b" response)
                (str "Expected 326 in response for " (:id case-entry) ", got: " response))
            (is (re-find #"årsverk" response)
                (str "Expected årsverk wording in response for " (:id case-entry) ", got: " response))
            (is (chunk-cited? result expected-citation)
                (str "Expected citation to chunk " expected-citation " for " (:id case-entry)
                     ", got citations " (pr-str (get-in result [:outputs :citations]))))))))))
