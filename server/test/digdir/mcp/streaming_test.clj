(ns digdir.mcp.streaming-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [digdir.mcp.streaming :as streaming]
            [ring.core.protocols :as ring-protocols])
  (:import (java.io ByteArrayOutputStream)))

(defn- drain-into-bytes!
  "Spin up the SseBody on the calling thread, writing into a
   ByteArrayOutputStream. Pre-pushes a few messages and an end sentinel
   so the writer terminates deterministically."
  [push-messages]
  (let [queue (streaming/make-queue)
        cancel? (atom false)
        body (streaming/->SseBody queue cancel?)
        out (ByteArrayOutputStream.)]
    (doseq [m push-messages] (streaming/push! queue m))
    (streaming/end-stream! queue)
    (ring-protocols/write-body-to-stream body {} out)
    (.toString out "UTF-8")))

(deftest sse-frames-are-well-formed
  (let [output (drain-into-bytes!
                 [{:jsonrpc "2.0"
                   :method "notifications/progress"
                   :params {:progressToken "p1"
                            :progress 1
                            :message "stage started"}}
                  {:jsonrpc "2.0"
                   :id 42
                   :result {:content [{:type "text" :text "done"}]}}])
        frames (str/split output #"\n\n")
        non-empty (remove str/blank? frames)]
    (is (= 2 (count non-empty)))
    (let [first-data (-> (first non-empty) (str/replace #"^data:\s*" ""))
          payload (json/parse-string first-data keyword)]
      (is (= "notifications/progress" (:method payload)))
      (is (= "p1" (get-in payload [:params :progressToken]))))
    (let [last-data (-> (last non-empty) (str/replace #"^data:\s*" ""))
          payload (json/parse-string last-data keyword)]
      (is (= 42 (:id payload))))))

(deftest progress-fn-maps-events-to-notifications
  (let [queue (streaming/make-queue)
        progress-fn (streaming/make-progress-fn queue "tok-1")]
    (progress-fn {:event :stage/started :stage :skills-retrieval :label "Retrieve"})
    (progress-fn {:event :agent/iteration-started :iteration 1 :max-iterations 3})
    (progress-fn {:event :tool/call :tool-call {:tool "search" :args {:q "x"}}})
    (progress-fn {:event :response/chunk :delta "Hello "})
    ;; Suppressed event — emits nothing.
    (progress-fn {:event :step/started :step-id :a :skill-id :b})
    (streaming/end-stream! queue)
    (let [drained (loop [acc []]
                    (let [item (.poll queue)]
                      (if (or (nil? item) (= ::end item) (= :digdir.mcp.streaming/end item))
                        acc
                        (recur (conj acc item)))))]
      (is (= 4 (count drained)))
      (let [methods (map :method drained)]
        (is (every? #(= "notifications/progress" %) methods)))
      (let [first-params (:params (first drained))]
        (is (= "tok-1" (:progressToken first-params)))
        (is (string? (:message first-params)))
        (is (= "stage/started" (get-in first-params [:_meta :event]))))
      (let [iter-params (:params (second drained))]
        (is (= 3 (:total iter-params)))
        (is (= "agent/iteration-started" (get-in iter-params [:_meta :event]))))
      (let [tool-params (:params (nth drained 2))]
        (is (= "search" (get-in tool-params [:_meta :tool-call :tool]))))
      (let [delta-params (:params (nth drained 3))]
        (is (= "Hello " (:message delta-params)))
        (is (= "Hello " (get-in delta-params [:_meta :delta])))))))
