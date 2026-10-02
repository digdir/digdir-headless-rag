(ns digdir.llm.provider-call-sites-test
  "Phase 1 of the provider-resolver change — the per-site differences between the two provider arms that
   the one-shape refactor had to KEEP, pinned at the transport.

   Most sites sent the same body on both arms and collapsed cleanly. These did
   not, and each keeps its difference as a condition on the RESOLVED provider
   (`(:provider spec)`), not as a second read of the switch:

   - `rag/synthesis` `rag-generate`, `sweep/judge` and `sweep/rechunk_reground`
     send `:stream false` on the generic-OpenAI arm only;
   - `query_planner`'s planner appends a closed `<think></think>` prefill on the
     generic-OpenAI arm only.

   The before-capture does not project `:stream` or the messages, so
   without this namespace dropping one of these conditions would stay green."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.test :refer [deftest is testing]]
            [digdir.llm.provider-fixtures :as fx]
            [digdir.rag.synthesis :as rag-synthesis]
            [digdir.secrets :as secrets]
            [digdir.skills.builtin.query-planner]
            [digdir.sweep.judge]
            [digdir.sweep.rechunk-reground]
            [wkok.openai-clojure.api :as wkok]))

(def ^:private tenant "call-sites-tenant")

(def ^:private install
  {"services.azure-openai.api-key" "az-key"
   "services.azure-openai.api-endpoint" "https://azure.call-sites.invalid"
   "services.azure-openai.deployment-name" "az-deployment"
   "services.azure-openai.model-name" "generic-model"})

(defn- sent
  "Run `f` with the switch at `switch` and both transports stubbed. Returns the
   one request body that left, as `{:transport … :body …}`: the wkok params on
   the wkok path, the decoded JSON on the direct POST."
  [switch f]
  (let [seen (atom [])
        reply {:choices [{:message {:role "assistant" :content "ok"}}]}]
    (fx/with-install (assoc install "services.azure-openai.use-azure-openai-api" switch)
      (fn []
        (with-redefs [secrets/get! (constantly "s")
                      wkok/create-chat-completion (fn [p & _] (swap! seen conj {:transport :wkok :body p}) reply)
                      http/post (fn [_ o]
                                  (swap! seen conj {:transport :direct :body (json/parse-string (:body o) true)})
                                  {:body reply})]
          (f))))
    (is (= 1 (count @seen)) "absolute: exactly one request left")
    (first @seen)))

(def ^:private messages [{:role "user" :content "x"}])

(deftest stream-false-is-sent-on-the-generic-openai-arm-only
  (doseq [[label call] [["rag-generate" #(rag-synthesis/rag-generate nil "convo" nil "prompt" {:tenant tenant})]
                        ["judge" #(@#'digdir.sweep.judge/call-model tenant messages "judge-model")]
                        ["rechunk" #(@#'digdir.sweep.rechunk-reground/call-model tenant messages "rechunk-model")]]]
    (testing label
      (let [openai (sent false call)
            azure (sent true call)]
        (is (false? (get-in openai [:body :stream])) (str label ": the generic-OpenAI arm sends stream false"))
        (is (not (contains? (:body azure) :stream)) (str label ": the Azure arm never sent :stream"))))))

(deftest the-planner-prefill-is-appended-on-the-generic-openai-arm-only
  (let [call #(@#'digdir.skills.builtin.query-planner/planner-completion
               tenant "planner-model" {:messages messages :temperature 0.0})
        last-message (fn [{:keys [body]}] (last (:messages body)))]
    (is (= {:role "assistant" :content "<think></think>"} (last-message (sent false call))))
    (is (= {:role "user" :content "x"} (last-message (sent true call))))))
