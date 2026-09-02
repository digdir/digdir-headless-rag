(ns digdir.mcp.cancellation-test
  "Tests for client-disconnect cancellation across the MCP streaming path.

   Two layers exercised here:
     1. The transport's cancel watcher: flipping `cancel?` on SseBody
        cancels the worker Future.
     2. The agent loop's `Thread/interrupted` check: an interrupted
        worker thread aborts the loop with InterruptedException."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest testing is]]
            [digdir.mcp.tools :as mcp-tools]
            [digdir.mcp.transport :as transport]
            [digdir.skills.builtin.agent.loop :as agent-loop]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.skills.test-helpers :as th]))

;; -- transport layer ----------------------------------------------------------

(defn- post
  "Builds a request with the metadata headers 2026-07-28 requires, mirroring
   the body — otherwise server validation rejects it before the streaming
   path is ever reached."
  [body]
  (let [params (:params body {})]
    {:request-method :post
     :uri "/api/mcp"
     :headers (cond-> {"mcp-protocol-version" "2026-07-28"
                       "mcp-method" (str (:method body))}
                (:name params) (assoc "mcp-name" (str (:name params))))
     :body (json/generate-string body)
     :api-key/id "k1"}))

(deftest sse-disconnect-cancels-worker-future
  (testing "When the SseBody flips :cancel? (e.g. on client IOException),
            the work Future is cancelled and never delivers the result"
    (let [started (promise)
          completed (promise)
          interrupted? (promise)]
      (with-redefs [mcp-tools/invoke-tool
                    (fn [_ _ _ _]
                      (deliver started true)
                      (try
                        ;; Simulate a long-running agent loop. The cancel
                        ;; watcher should call Future.cancel(true) which
                        ;; interrupts this sleep.
                        (Thread/sleep 5000)
                        (deliver completed true)
                        {:result {:content [{:type "text" :text "ok"}]
                                  :isError false
                                  :_meta {}}}
                        (catch InterruptedException _
                          (deliver interrupted? true)
                          (throw (InterruptedException. "interrupted")))))]
        (let [resp (transport/handle-mcp-request
                     (post {:jsonrpc "2.0" :id 1 :method "tools/call"
                            :params {:name "agent__graph"
                                     :arguments {:query "x"}
                                     :_meta {:progressToken "tok"}}}))
              ;; The body is the SseBody record carrying a cancel-atom in
              ;; its second field. Pull it out and flip it.
              cancel? (.cancel-atom ^digdir.mcp.streaming.SseBody (:body resp))]
          (is (true? (deref started 2000 false)))
          (reset! cancel? true)
          (is (true? (deref interrupted? 2000 false))
              "Worker future should be interrupted when cancel? flips")
          (is (false? (deref completed 100 false))
              "Worker should NOT complete normally after cancel"))))))

;; -- agent loop layer ---------------------------------------------------------

(deftest agent-loop-honors-thread-interrupted
  (testing "Pre-interrupted thread aborts the loop on the next iteration"
    (let [!ws (workspace/create-workspace)
          messages [{:role "system" :content "s"}
                    {:role "user" :content "q"}]
          ambient-ctx {:docs-collection "docs"
                       :chunks-collection "chunks"
                       :phrases-collection "phrases"
                       :conversation-history []
                       :opts {:tenant "t" :environment "e"}}]
      (with-redefs [agent-loop/call-llm (th/llm-returning-json "{}")]
        (.interrupt (Thread/currentThread))
        (is (thrown? InterruptedException
                     (agent-loop/agentic-loop messages !ws ambient-ctx
                                              {:max-iterations 5})))
        ;; Clear the interrupted flag so it doesn't leak into other tests.
        (Thread/interrupted)))))
