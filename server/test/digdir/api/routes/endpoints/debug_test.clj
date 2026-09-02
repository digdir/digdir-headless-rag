(ns digdir.api.routes.endpoints.debug-test
  "Tests for the /api/debug/last-invocation recorder + handler and the
   /api/debug/agent-resolution synchronous resolver.

   Both endpoints are Layer-C E2E testability scaffolds — the recorder
   captures the most-recent resolved skill-params per agent so Playwright
   tests can verify that an agent's :skill-params actually shaped the
   call, and the resolver synchronously echoes back the merge result
   without needing the MCP path. Both are gated on the same env var so
   production traffic never pays the cost; these tests exercise both
   the enabled and disabled paths via with-redefs."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest testing is]]
            [digdir.agents.db]
            [digdir.api.routes.endpoints.debug :as debug]))

(defn- read-edn-body
  [response]
  (edn/read-string (:body response)))

(defn- with-capture-enabled
  "Re-bind `capture-enabled?` to return true for a body. Mirrors how the
   handler sees env vars in production."
  [body-fn]
  (with-redefs [debug/capture-enabled? (constantly true)]
    (body-fn)))

(defn- with-capture-disabled
  [body-fn]
  (with-redefs [debug/capture-enabled? (constantly false)]
    (body-fn)))

(deftest test-recorder-no-op-when-disabled
  (testing "record-last-invocation! is a no-op when capture is disabled"
    (with-capture-disabled
      (fn []
        (debug/record-last-invocation!
          "agent/test"
          {:user-query "q" :skill-params {:foo :bar} :source :test})
        ;; Handler should report 503 because nothing was captured AND
        ;; capture itself is off.
        (let [resp (debug/debug-last-invocation-handler
                     {:params {"agent-id" "agent/test"}})]
          (is (= 503 (:status resp))
              "Disabled capture returns 503 so test fixtures fail loud."))))))

(deftest test-recorder-and-handler-roundtrip
  (testing "Recording an invocation makes it retrievable via the handler"
    (with-capture-enabled
      (fn []
        (let [params {:builtin/retrieval {:strategy-weights {:phrase 1.0}}
                      :builtin/rerank {:top-k 20}}]
          (debug/record-last-invocation!
            "agent/tuned"
            {:user-query "test query"
             :skill-params params
             :skill-graph-id "builtin/agent-rag-graph-faithful"
             :source :unit-test})
          (let [resp (debug/debug-last-invocation-handler
                       {:params {"agent-id" "agent/tuned"}})
                body (read-edn-body resp)]
            (is (= 200 (:status resp)))
            (is (= "agent/tuned" (:agent-id body)))
            (is (= "test query" (:user-query body)))
            (is (= params (:skill-params body))
                "The recorded skill-params survive the round-trip unchanged.")
            (is (= "builtin/agent-rag-graph-faithful" (:skill-graph-id body)))
            (is (= :unit-test (:source body)))
            (is (some? (:captured-at-ms body)))))))))

(deftest test-handler-404-on-unknown-agent
  (testing "Handler returns 404 when no invocation has been recorded for the agent"
    (with-capture-enabled
      (fn []
        (let [resp (debug/debug-last-invocation-handler
                     {:params {"agent-id" "agent/never-recorded"}})]
          (is (= 404 (:status resp)))
          (is (= "agent/never-recorded" (:agent-id (read-edn-body resp)))))))))

(deftest test-handler-400-on-missing-agent-param
  (testing "Handler returns 400 when no agent-id query param is supplied"
    (with-capture-enabled
      (fn []
        (let [resp (debug/debug-last-invocation-handler {:params {}})]
          (is (= 400 (:status resp))))))))

(deftest test-handler-accepts-both-agent-and-agent-id
  (testing "Both ?agent= and ?agent-id= work as the query param (camelCase ergonomics)"
    (with-capture-enabled
      (fn []
        (debug/record-last-invocation!
          "agent/either"
          {:user-query "q" :skill-params {} :source :test})
        (let [resp-long (debug/debug-last-invocation-handler
                          {:params {"agent-id" "agent/either"}})
              resp-short (debug/debug-last-invocation-handler
                           {:params {"agent" "agent/either"}})]
          (is (= 200 (:status resp-long)))
          (is (= 200 (:status resp-short))))))))

(deftest test-multiple-agents-tracked-independently
  (testing "Each agent-id has its own slot — recording one doesn't clobber another"
    (with-capture-enabled
      (fn []
        (debug/record-last-invocation!
          "agent/a"
          {:user-query "qa" :skill-params {:a 1} :source :t})
        (debug/record-last-invocation!
          "agent/b"
          {:user-query "qb" :skill-params {:b 2} :source :t})
        (let [resp-a (debug/debug-last-invocation-handler
                       {:params {"agent-id" "agent/a"}})
              resp-b (debug/debug-last-invocation-handler
                       {:params {"agent-id" "agent/b"}})]
          (is (= "qa" (:user-query (read-edn-body resp-a))))
          (is (= "qb" (:user-query (read-edn-body resp-b))))
          (is (= {:a 1} (:skill-params (read-edn-body resp-a))))
          (is (= {:b 2} (:skill-params (read-edn-body resp-b)))))))))

;; =============================================================================
;; /api/debug/agent-resolution — synchronous merge resolution scaffold
;; =============================================================================

(defn- with-agent
  "Stub agents-db/get-agent to return `agent-map` for `agent-id`, nil
   otherwise. Calls the body fn with capture also enabled."
  [agent-id agent-map body-fn]
  (with-redefs [debug/capture-enabled? (constantly true)
                digdir.agents.db/get-agent (fn [_db looked-up]
                                             (when (= looked-up agent-id)
                                               agent-map))]
    (body-fn)))

(deftest test-agent-resolution-default-agent
  (testing "Agent with empty :skill-params resolves to just the hardcoded defaults"
    (with-agent "e2e/default"
      {:id "e2e/default" :enabled? true :skill-params {}}
      (fn []
        (let [resp (debug/debug-agent-resolution-handler
                     {:params {"agent-id" "e2e/default"}})
              body (read-edn-body resp)]
          (is (= 200 (:status resp)))
          (is (= "e2e/default" (:agent-id body)))
          (is (true? (:found? body)))
          (is (= {} (:agent-skill-params body)))
          (is (= 100 (get-in body [:resolved-skill-params :builtin/retrieval :retrieve-top-k]))
              "Hardcoded floor (retrieve-top-k 100) leaks through even on empty agent.")
          (is (true? (get-in body [:resolved-skill-params :builtin/retrieval :query-aware-boost]))
              "Hardcoded floor (query-aware-boost true) leaks through too."))))))

(deftest test-agent-resolution-tuned-agent
  (testing "Agent skill-params overrides surface in :resolved-skill-params"
    (let [tuned-skill-params {:builtin/retrieval {:strategy-weights {:content 0.0
                                                                     :phrase 1.0
                                                                     :metadata 0.0}
                                                  :strategy-contribution-caps {:phrase 5
                                                                               :content 0
                                                                               :metadata 0}
                                                  :retrieve-top-k 100}
                              :builtin/rerank {:top-k 20}}]
      (with-agent "e2e/tuned"
        {:id "e2e/tuned" :enabled? true :skill-params tuned-skill-params}
        (fn []
          (let [resp (debug/debug-agent-resolution-handler
                       {:params {"agent-id" "e2e/tuned"}})
                body (read-edn-body resp)]
            (is (= 200 (:status resp)))
            (is (= "e2e/tuned" (:agent-id body)))
            (is (= tuned-skill-params (:agent-skill-params body))
                "Raw agent skill-params echo back unchanged.")
            (is (= {:content 0.0 :phrase 1.0 :metadata 0.0}
                   (get-in body [:resolved-skill-params :builtin/retrieval :strategy-weights]))
                "Phrase-only weights flow through the merge.")
            (is (= {:phrase 5 :content 0 :metadata 0}
                   (get-in body [:resolved-skill-params :builtin/retrieval :strategy-contribution-caps]))
                "Strategy contribution caps flow through.")
            (is (= 20 (get-in body [:resolved-skill-params :builtin/rerank :top-k]))
                "Rerank top-k override flows through.")))))))

(deftest test-agent-resolution-404-on-unknown-agent
  (testing "Unknown agent returns 404 with :found? false"
    (with-redefs [debug/capture-enabled? (constantly true)
                  digdir.agents.db/get-agent (fn [_db _id] nil)]
      (let [resp (debug/debug-agent-resolution-handler
                   {:params {"agent-id" "e2e/never-seeded"}})
            body (read-edn-body resp)]
        (is (= 404 (:status resp)))
        (is (= "e2e/never-seeded" (:agent-id body)))
        (is (false? (:found? body)))))))

(deftest test-agent-resolution-400-on-missing-param
  (testing "Missing agent-id returns 400"
    (with-redefs [debug/capture-enabled? (constantly true)]
      (let [resp (debug/debug-agent-resolution-handler {:params {}})]
        (is (= 400 (:status resp)))))))

(deftest test-agent-resolution-503-when-capture-disabled
  (testing "Disabled capture returns 503 — same gate as last-invocation"
    (with-redefs [debug/capture-enabled? (constantly false)]
      (let [resp (debug/debug-agent-resolution-handler
                   {:params {"agent-id" "e2e/default"}})]
        (is (= 503 (:status resp)))))))

(deftest test-agent-resolution-accepts-both-agent-and-agent-id
  (testing "Both ?agent= and ?agent-id= work, matching the recorder endpoint"
    (with-agent "e2e/tuned"
      {:id "e2e/tuned" :enabled? true :skill-params {:builtin/retrieval {:retrieve-top-k 33}}}
      (fn []
        (let [resp-long (debug/debug-agent-resolution-handler
                          {:params {"agent-id" "e2e/tuned"}})
              resp-short (debug/debug-agent-resolution-handler
                           {:params {"agent" "e2e/tuned"}})]
          (is (= 200 (:status resp-long)))
          (is (= 200 (:status resp-short))))))))

;; ---------------------------------------------------------------------------
;; Response key sets (#195)
;;
;; The assertions above read individual fields. A key the handler should not
;; emit is invisible to them, because nothing looks at it — which is how a
;; fabricated `config-key` lived in a test response indefinitely (#191).
;;
;; Both of these shapes are built as map literals in debug.clj, so unlike a
;; data-driven body they are genuinely closed and can be asserted as equality
;; rather than as a subset.

(deftest last-invocation-record-has-exactly-the-recorded-fields
  (testing "the capture record is the six fields record-last-invocation! writes"
    (with-capture-enabled
      (fn []
        (debug/record-last-invocation!
          "agent/keyset"
          {:user-query "q" :skill-params {} :skill-graph-id "g" :source :unit-test})
        (let [body (read-edn-body
                     (debug/debug-last-invocation-handler
                       {:params {"agent-id" "agent/keyset"}}))]
          (is (= #{:agent-id :user-query :skill-params :skill-graph-id
                   :source :captured-at-ms}
                 (set (keys body)))
              (str "capture record keys drifted: " (pr-str (sort (keys body))))))))))

(deftest agent-resolution-responses-have-exactly-their-documented-fields
  (testing "the found and not-found shapes are both closed"
    ;; :enabled? is asserted here and nowhere else — it is emitted by the
    ;; handler and no value assertion above reads it, which is exactly the
    ;; blind spot a key-set assertion closes.
    (with-agent "e2e/keyset"
      {:id "e2e/keyset" :enabled? true :skill-params {}}
      (fn []
        (let [body (read-edn-body
                     (debug/debug-agent-resolution-handler
                       {:params {"agent-id" "e2e/keyset"}}))]
          (is (= #{:agent-id :found? :enabled? :agent-skill-params
                   :resolved-skill-params}
                 (set (keys body)))
              (str "agent-resolution (found) keys drifted: "
                   (pr-str (sort (keys body))))))))
    (with-agent "e2e/keyset"
      {:id "e2e/keyset" :enabled? true :skill-params {}}
      (fn []
        (let [body (read-edn-body
                     (debug/debug-agent-resolution-handler
                       {:params {"agent-id" "e2e/absent"}}))]
          (is (= #{:error :agent-id :found?} (set (keys body)))
              (str "agent-resolution (not found) keys drifted: "
                   (pr-str (sort (keys body))))))))))
