(ns digdir.api.body-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [digdir.api.body :as body]
            [digdir.api.routes.endpoints.openai-compat :as openai]
            [digdir.api.util :as api-util]
            [digdir.mcp.transport :as mcp])
  (:import (java.io ByteArrayInputStream StringReader)))

(defn- body-stream [s]
  (ByteArrayInputStream. (.getBytes ^String s "UTF-8")))

(deftest bounded-body-reader
  (testing "accepts bodies exactly at the limit"
    (is (= "1234" (body/read-body-string {:body (body-stream "1234")} 4)))
    (is (= "1234" (body/read-body-string {:body (StringReader. "1234")} 4))))
  (testing "rejects streamed, string, and declared oversized bodies"
    (doseq [request [{:body (body-stream "12345")}
                     {:body "12345"}
                     {:body (body-stream "x") :headers {"content-length" "5"}}]]
      (let [error (try
                    (body/read-body-string request 4)
                    nil
                    (catch Exception e e))]
        (is (body/body-too-large? error))
        (is (= 413 (:status (ex-data error)))))))
  (testing "the limit is measured in UTF-8 bytes, not characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"too large"
                          (body/read-body-string {:body "øøø"} 5)))))

(deftest general-api-reader-preserves-413
  (let [request {:body "{}"
                 :headers {"content-length"
                           (str (inc body/default-max-json-body-bytes))}}
        error (try
                (api-util/read-json-body request)
                nil
                (catch Exception e e))]
    (is (body/body-too-large? error))
    (is (= 413 (:status (ex-data error))))))

(deftest openai-compat-returns-413
  (let [response (openai/chat-completions-handler
                  {:body "{}"
                   :headers {"content-length"
                             (str (inc body/default-max-json-body-bytes))}})
        parsed (json/parse-string (:body response) true)]
    (is (= 413 (:status response)))
    (is (= "request_too_large" (get-in parsed [:error :code])))))

(deftest mcp-returns-413
  (let [response (mcp/handle-mcp-request
                  {:body "{}"
                   :headers {"content-length"
                             (str (inc body/default-max-json-body-bytes))}})]
    (is (= 413 (:status response)))
    (is (= -32700 (get-in (json/parse-string (:body response) true)
                           [:error :code])))))
