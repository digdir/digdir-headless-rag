(ns digdir.playground.status-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.playground.chat-session :as chat-session]
            [digdir.playground.core :as core]
            [digdir.playground.live-status-scheduler :as live-status-scheduler]
            [digdir.playground.status :as status]))

(deftest execution-status-snapshot-captures-summary-and-narrative
  (testing "Snapshot collapses execution events into a compact immutable shape"
    (let [snapshot (status/execution-status-snapshot
                    {:stage :skills-query-planning
                     :events [{:event :request/started :query "Q"}
                              {:event :stage/started :stage :skills-query-planning :label "Planning queries"}
                              {:event :tool/result :tool-result {:tool "search_documents"
                                                                 :summary "Found 12 chunks"
                                                                 :ok? true}}
                              {:event :warning/raised :warning {:code :fallback-used
                                                                :message "Used broadened retrieval"}}
                              {:event :stage/completed :stage :skills-query-planning
                               :label "Planning queries"
                               :duration-ms 42}]})]
      (is (= 5 (:snapshot-id snapshot)))
      (is (= :skills-query-planning (:current-stage snapshot)))
      (is (= "Completed Planning queries (42ms)" (:summary snapshot)))
      (is (= "Found 12 chunks" (:tool-narrative snapshot)))
      (is (= 1 (:tool-result-count snapshot)))
      (is (= 1 (:warning-count snapshot))))))

(deftest synthesized-status-summary-falls-back-to-stage-label
  (testing "The synthesized artifact records when it had to fall back to presentation-only data"
    (let [artifact (status/synthesize-status-summary
                    (status/execution-status-snapshot
                     {:stage :init
                      :events []}))]
      (is (= :playground/live-status-summary (:artifact-type artifact)))
      (is (= 0 (:source-snapshot-id artifact)))
      (is (= "Initializing" (:summary artifact)))
      (is (= true (:fallback-used? artifact)))
      (is (= false (:stale? artifact))))))

(deftest resolve-live-status-prefers-fresh-synthesized-summary
  (testing "Fresh synthesized status overrides the event-derived label, while stale artifacts fall back cleanly"
    (let [execution {:stage :skills-generating
                     :events [{:event :request/started :query "Q"}
                              {:event :stage/started :stage :skills-generating :label "Generating response"}]
                     :live-status {:artifact-type :playground/live-status-summary
                                   :source-snapshot-id 2
                                   :summary "Synthesized status"
                                   :stale? false}}
          live-view (chat-session/execution-events->live-view (:events execution))
          resolved (status/resolve-live-status execution live-view)
          stale-resolved (status/resolve-live-status
                          (assoc execution :live-status (assoc (:live-status execution)
                                                               :source-snapshot-id 1))
                          live-view)]
      (is (= "Synthesized status" (:live-summary resolved)))
      (is (= :synthesized (:live-status-source resolved)))
      (is (= false (:live-status-stale? resolved)))
      (is (= "Started Generating response" (:summary live-view)))
      (is (= "Started Generating response" (:live-summary stale-resolved)))
      (is (= :stale (:live-status-source stale-resolved)))
      (is (= true (:live-status-stale? stale-resolved))))))

(deftest emit-execution-event-publishes-live-status-sidecar-artifact
  (testing "Execution events trigger opportunistic live-status publication without changing the core event stream"
    (let [execution-id "exec-live-status-test"]
      (swap! core/!playground-executions assoc execution-id
             {:status :running
              :stage :skills-query-planning
              :streaming-content ""
              :events []
              :results {}
              :error nil
              :started-at "2026-04-15T12:00:00Z"})
      (try
        (core/emit-execution-event! execution-id
                                    {:event :stage/started
                                     :stage :skills-query-planning
                                     :label "Planning queries"})
        (loop [attempt 0]
          (when (and (< attempt 50)
                     (nil? (:live-status (core/get-execution execution-id))))
            (Thread/sleep 10)
            (recur (inc attempt))))
        (let [execution (core/get-execution execution-id)
              view (chat-session/execution-stream-view execution)]
          (is (= 1 (count (:events execution))))
          (is (some? (:live-status execution)))
          (is (= "Started Planning queries" (:live-summary view)))
          (is (= :synthesized (:live-status-source view)))
          (is (= false (:live-status-stale? view))))
        (finally
          (swap! core/!playground-executions dissoc execution-id)
          (swap! live-status-scheduler/!live-status-jobs dissoc execution-id))))))
